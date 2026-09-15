# Loyalty Microservice Platform — Documento Funcional

## Contexto de negocio

Plataforma de fidelización que permite a un usuario transferir puntos de su saldo a otra cuenta. Nace de un reto técnico de un solo endpoint (`POST /api/v1/points/transfer`) y se amplió, por decisión explícita del usuario, a un sistema completo con gestión de cuentas y usuarios, con fines de práctica personal.

## Entidades de negocio

- **Usuario**: se registra/autentica vía Keycloak. Puede tener N cuentas.
- **Cuenta (Account)**: pertenece a un usuario (`ownerId` = `sub` del JWT). Tiene saldo (`balance`) y estado (`activa`/`inactiva`). ID de negocio tipo `acc-xxxx`.
- **Transacción (Transaction)**: registro de una transferencia de puntos entre dos cuentas. Incluye UUID, timestamp, cuentas origen/destino, monto, estado.

## Requerimientos funcionales

### 1. Transferencia de puntos
`POST /api/v1/points/transfer`

Payload:
```json
{ "sourceAccountId": "acc-101", "targetAccountId": "acc-202", "amount": 150 }
```

Respuesta exitosa (200/201): UUID de transacción, fecha/hora, balances actualizados o estado de la operación.

**Reglas de negocio:**
- `amount` estrictamente mayor a 0 (validación declarativa `jakarta.validation`).
- Cuenta origen y destino deben ser distintas.
- Cuenta origen debe existir, estar **activa** y tener saldo suficiente.
- Cuenta destino debe existir y estar **activa**.
- La operación debe ser atómica: si falla el crédito en destino, no debe persistirse el débito en origen.
- El usuario autenticado debe ser dueño de `sourceAccountId` (o tener rol `ADMIN`).

**Errores HTTP:**
- `400 Bad Request`: payload inválido o cuentas iguales.
- `404 Not Found`: alguna cuenta no existe.
- `422 Unprocessable Entity` (o `409 Conflict`): saldo insuficiente o cuenta inactiva (origen o destino).
- `403 Forbidden`: usuario autenticado no es dueño de la cuenta origen y no es `ADMIN`.
- Manejador global de excepciones (`@RestControllerAdvice`) estandariza la respuesta de error (código, mensaje, timestamp).

### 2. Gestión de cuentas
- `POST /accounts` — crear cuenta (saldo inicial, dueño = usuario autenticado).
- `GET /accounts/{id}` — consultar cuenta y saldo (dueño o `ADMIN`).
- `PATCH /accounts/{id}/status` — activar/bloquear cuenta (solo `ADMIN`).
- `GET /accounts/{id}/transactions` — historial de transferencias de una cuenta (dueño o `ADMIN`).

### 3. Gestión de usuarios / autenticación
- Registro y login delegados a **Keycloak** (Identity Provider externo).
- Los servicios de negocio actúan como **Resource Server**: validan el JWT emitido por Keycloak, no gestionan contraseñas.
- Un `auth-service` propio actúa como fachada sobre la Admin API de Keycloak para registro (ver Documento de Arquitectura).

## Roles y permisos

| Rol | Permisos |
|---|---|
| `USER` | Crear/consultar sus propias cuentas, transferir desde cuentas propias, ver su propio historial |
| `ADMIN` | Todo lo de `USER` sobre cualquier cuenta, además activar/bloquear cuentas |

## Fuera de alcance (decisión explícita)

- Recuperación de contraseña, MFA, flujos avanzados de Keycloak (se usa configuración estándar).
- Múltiples monedas / tipos de puntos (solo un tipo de saldo numérico).
- Límites de transferencia (por monto/frecuencia) — no mencionados en el enunciado.

## Fases de entrega funcional

1. Infraestructura base (sin funcionalidad de negocio visible).
2. `account-service`: CRUD de cuentas, saldo, bloqueo, ownership.
3. `transfer-service`: transferencia de puntos con saga coreografiada.
4. `auth-service`: registro/login como fachada de Keycloak.
5. Gateway: punto de entrada único.
6. Testing end-to-end de todo el flujo funcional.
