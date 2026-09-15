# Loyalty Microservice Platform — Documento Funcional

## Contexto de negocio

Plataforma de fidelización que permite a un usuario transferir puntos de su saldo a otra cuenta. Nace de un reto técnico de un solo endpoint (`POST /api/v1/points/transfer`) y se amplió, por decisión explícita del usuario, a un sistema completo con gestión de cuentas y usuarios, con fines de práctica personal (no es un requisito de una evaluación externa — ver decisión D1).

## Entidades de negocio

### Usuario
Se registra/autentica vía Keycloak. No existe un modelo de `Usuario` propio en la base de datos de ningún microservicio de negocio — Keycloak es la única fuente de verdad de identidad. Puede tener **N cuentas**.

Atributos gestionados por Keycloak: `username`/`email`, `password` (hasheada por Keycloak), `sub` (UUID, identificador único del usuario, usado como `ownerId` en las cuentas), roles (`USER`, `ADMIN`).

### Cuenta (Account)
| Campo | Tipo | Descripción |
|---|---|---|
| `id` | String (`acc-xxxx`) | Identificador de negocio, `_id` en Mongo |
| `ownerId` | String (UUID) | `sub` del JWT del usuario dueño |
| `balance` | Number (entero, ≥ 0) | Saldo actual en puntos |
| `status` | Enum: `ACTIVE` \| `INACTIVE` | Estado operativo de la cuenta |
| `createdAt` | Timestamp | Fecha de creación |
| `updatedAt` | Timestamp | Última modificación (saldo o estado) |

### Transacción (Transaction)
| Campo | Tipo | Descripción |
|---|---|---|
| `id` | String (UUID) | Identificador único de la transacción |
| `sourceAccountId` | String | Cuenta origen |
| `targetAccountId` | String | Cuenta destino |
| `amount` | Number (entero, > 0) | Monto transferido |
| `status` | Enum: `PENDING` \| `COMPLETED` \| `FAILED` | Estado de la saga (ver Documento de Arquitectura §Saga) |
| `failureReason` | String (opcional) | Motivo si `status = FAILED` (ej. `INSUFFICIENT_BALANCE`, `TARGET_INACTIVE`) |
| `createdAt` | Timestamp | Fecha/hora de la solicitud |
| `completedAt` | Timestamp (opcional) | Fecha/hora en que se resolvió (éxito o fallo definitivo) |

## Requerimientos funcionales

### 1. Transferencia de puntos

`POST /api/v1/points/transfer`

**Request:**
```json
{
  "sourceAccountId": "acc-101",
  "targetAccountId": "acc-202",
  "amount": 150
}
```

**Response exitosa — `202 Accepted`** (no `200`/`201`, ver decisión D9 en Arquitectura: la operación es asíncrona por la saga):
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PENDING",
  "createdAt": "2026-09-15T10:00:00Z"
}
```

El cliente consulta el estado final vía `GET /accounts/{id}/transactions` o (si se agrega en fase posterior) `GET /transactions/{id}`.

**Reglas de negocio:**
- `amount` estrictamente mayor a 0 (validación declarativa `jakarta.validation`: `@Positive`).
- `sourceAccountId` y `targetAccountId` deben ser distintos (`@AssertTrue` a nivel de DTO, o validación manual en el controller).
- Cuenta origen debe existir, estar `ACTIVE` y tener `balance >= amount`.
- Cuenta destino debe existir y estar `ACTIVE`.
- La operación debe ser atómica end-to-end: si falla el crédito en destino, se compensa el débito en origen (ver saga en Arquitectura). El cliente nunca ve un estado intermedio inconsistente en el saldo de las cuentas involucradas.
- El usuario autenticado debe ser dueño de `sourceAccountId` (`jwt.sub == account.ownerId`) o tener rol `ADMIN`.

**Errores HTTP (validación síncrona, antes de iniciar la saga):**
- `400 Bad Request`: payload inválido (`amount <= 0`, campos faltantes) o `sourceAccountId == targetAccountId`.
- `404 Not Found`: `sourceAccountId` o `targetAccountId` no existen.
- `403 Forbidden`: el usuario autenticado no es dueño de `sourceAccountId` y no es `ADMIN`.
- `422 Unprocessable Entity`: saldo insuficiente o alguna cuenta inactiva — **detectado en la validación síncrona previa a emitir el evento** (chequeo optimista); el resultado definitivo de la saga puede aun así terminar en `FAILED` si hay una condición de carrera entre la validación y el procesamiento asíncrono (ver Arquitectura §Saga, consistencia eventual).
- Manejador global de excepciones (`@RestControllerAdvice`) estandariza toda respuesta de error: `{ "code": "...", "message": "...", "timestamp": "..." }`.

### 2. Gestión de cuentas

| Endpoint | Método | Autorización | Descripción |
|---|---|---|---|
| `/accounts` | `POST` | `USER` autenticado | Crea cuenta con `ownerId = jwt.sub`, `balance` inicial (0 por defecto o especificado en el body), `status = ACTIVE` |
| `/accounts/{id}` | `GET` | Dueño o `ADMIN` | Devuelve cuenta completa incluyendo saldo |
| `/accounts/{id}/status` | `PATCH` | Solo `ADMIN` | Cambia `status` a `ACTIVE`/`INACTIVE`. Body: `{ "status": "INACTIVE" }` |
| `/accounts/{id}/transactions` | `GET` | Dueño o `ADMIN` | Lista transacciones donde la cuenta es origen o destino, paginado |

**Reglas:**
- Un usuario `USER` no puede ver ni modificar cuentas que no le pertenecen (`403 Forbidden` si lo intenta).
- Una cuenta `INACTIVE` no puede ser origen ni destino de una transferencia (ver §1).
- No existe endpoint de borrado de cuentas en el alcance actual (fuera de alcance).

### 3. Gestión de usuarios / autenticación

- Registro (`POST /auth/register` vía `auth-service`) y login (`POST /auth/login`, delegado directamente al endpoint de token de Keycloak, `auth-service` puede actuar de passthrough o el cliente llama a Keycloak directamente — decisión de detalle en Fase 3).
- Los servicios de negocio (`account-service`, `transfer-service`) actúan como **Resource Server**: solo validan el JWT emitido por Keycloak, nunca gestionan contraseñas.
- `auth-service` es el único componente que habla con la **Admin API de Keycloak**, para crear usuarios programáticamente durante el registro.

## Roles y permisos

| Rol | Permisos |
|---|---|
| `USER` | Crear cuentas propias, consultar/transferir desde cuentas propias, ver su propio historial |
| `ADMIN` | Todo lo de `USER` sobre **cualquier** cuenta, además activar/bloquear cualquier cuenta |

Un usuario puede tener ambos roles asignados (ej. un admin que también opera sus propias cuentas).

## Fuera de alcance (decisión explícita)

- Recuperación de contraseña, MFA, flujos avanzados de Keycloak (se usa configuración estándar de Keycloak `start-dev`).
- Múltiples monedas / tipos de puntos (solo un tipo de saldo numérico entero).
- Límites de transferencia (por monto/frecuencia) — no mencionados en el enunciado original ni pedidos por el usuario.
- Borrado de cuentas o usuarios.
- Notificaciones (email/push) al completar una transferencia.
- Endpoint de consulta de transacción individual por ID (`GET /transactions/{id}`) — se puede agregar en una fase futura si se necesita polling directo en vez de vía historial de cuenta.

## Fases de entrega funcional

1. **Infraestructura base** — sin funcionalidad de negocio visible; solo Mongo/Keycloak/Kafka/Eureka operativos.
2. **`account-service`** — CRUD de cuentas, saldo, bloqueo, ownership, consumidor de eventos de saga.
3. **`transfer-service`** — endpoint `/transfer`, productor/consumidor de eventos, orquestación de la saga y su compensación.
4. **`auth-service`** — registro/login como fachada de Keycloak.
5. **Gateway** — punto de entrada único, enrutamiento vía Eureka.
6. **Testing end-to-end** — flujo completo: registro → creación de cuenta → transferencia → resolución de saga → consulta de historial.

## Historial de decisiones funcionales

Ver `docs/ARQUITECTURA.md §Decisiones y alternativas descartadas` para el detalle completo de cada decisión (D1–D12) con su justificación y alternativas evaluadas. Resumen de las que impactan directamente el comportamiento funcional visible:

- **D1 — Alcance real del ejercicio**: es práctica personal, no evaluación EPAM/NEORIS real. Esto justifica que el sistema final se aleje del enunciado original (que pedía JPA + un solo endpoint) para maximizar aprendizaje sobre Mongo/Keycloak/Kafka/microservicios.
- **D4 — Estado de cuenta origen**: se decidió validar que **ambas** cuentas (origen y destino) estén `ACTIVE`, ampliando el enunciado original que solo mencionaba la cuenta destino.
- **D6 — Alcance de endpoints de cuenta**: se agregaron los 4 endpoints de `/accounts` (crear, consultar, cambiar estado, historial) que no estaban en el enunciado original, para que el sistema sea operable de punta a punta.
- **D9 — Respuesta asíncrona de `/transfer`**: al introducirse la saga coreografiada, la respuesta ya no puede devolver balances actualizados de forma síncrona (`200`/`201` como pedía el enunciado original) — se documenta como `202 Accepted` con `status: PENDING`, y el resultado final se consulta vía historial.
