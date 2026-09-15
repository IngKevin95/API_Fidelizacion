# Loyalty Microservice Platform — Documento de Arquitectura

## Resumen

Arquitectura de microservicios coreografiada por eventos, con Keycloak como Identity Provider, Kafka como bus de eventos para la saga de transferencia, Eureka para service discovery y Spring Cloud Gateway como punto de entrada único. Elegida por decisión explícita del usuario con fin de práctica personal (el reto original solo pedía un endpoint monolítico).

## Servicios

| Servicio | Responsabilidad | Dueño de datos |
|---|---|---|
| `account-service` | CRUD de cuentas, saldo, bloqueo/activación, ownership. Consume eventos de débito/crédito y aplica cambios atómicos de saldo. Emite eventos de resultado (éxito/fallo) | `accounts` (Mongo) |
| `transfer-service` | Recibe `POST /transfer`, valida reglas de negocio de alto nivel, orquesta la saga emitiendo eventos, persiste el registro de `Transaction` con su estado final | `transactions` (Mongo) |
| `auth-service` | Fachada de registro sobre la Admin API de Keycloak; no gestiona contraseñas directamente | Ninguno propio (delega a Keycloak) |
| `gateway` | Punto de entrada único; enruta a los servicios vía Eureka | N/A |
| `eureka-server` | Service discovery | N/A |

## Persistencia

- **MongoDB**, un replica set de 1 nodo (`rs0`) — necesario para transacciones ACID multi-documento nativas de Mongo, usadas dentro de cada servicio (no cross-servicio).
- IDs de negocio propios (`acc-xxxx`, UUID para transacciones) como `_id`, no `ObjectId` nativo expuesto.
- Cada servicio tiene su propia base de datos lógica (database-per-service): `account-service` no lee directamente la colección de `transactions` ni viceversa.

## Atomicidad de la transferencia: Saga coreografiada

La transferencia cruza dos "cuentas" que viven en el mismo `account-service`, pero se orquesta como saga para practicar el patrón (y porque `transfer-service` no comparte base de datos con `account-service`):

1. `transfer-service` recibe `/transfer`, valida formato y ownership, persiste `Transaction` en estado `PENDING`.
2. Emite evento `DebitRequested` al topic `debit-events`.
3. `account-service` consume el evento; ejecuta un **update atómico condicional** (`findOneAndUpdate` con filtro `balance >= amount`, no locking pesimista clásico — no aplica en Mongo). Si tiene éxito, emite `DebitSucceeded`; si falla (saldo insuficiente o cuenta inactiva), emite `DebitFailed`.
4. `transfer-service` consume `DebitSucceeded` → emite `CreditRequested` al topic `credit-events`. Si recibe `DebitFailed`, marca `Transaction` como `FAILED` y termina (nada que compensar, el débito no se aplicó).
5. `account-service` consume `CreditRequested`; aplica crédito atómico en destino. Si tiene éxito, emite `CreditSucceeded`; si falla (cuenta destino inactiva o inexistente — validado antes, pero se re-verifica), emite `CreditFailed`.
6. `transfer-service` consume el resultado:
   - `CreditSucceeded` → marca `Transaction` como `COMPLETED`.
   - `CreditFailed` → emite evento de **compensación** al topic `transfer-compensation`; `account-service` revierte el débito original (crédito de vuelta a origen); `transfer-service` marca `Transaction` como `FAILED`.

**Topics Kafka:** `debit-events`, `credit-events`, `transfer-compensation`.

Nota de diseño: dado que en la topología actual solo `account-service` posee ambas cuentas, esta saga es más compleja que resolver el débito+crédito como una única transacción Mongo local — se adopta igualmente por el objetivo declarado de practicar el patrón saga/Kafka.

## Seguridad

- **Keycloak** como Identity Provider (realm `loyalty-realm`, roles `USER`/`ADMIN`, client `loyalty-app`).
- Cada servicio de negocio (`account-service`, `transfer-service`) es un **OAuth2 Resource Server**: valida el JWT firmado por Keycloak vía Spring Security (`spring-boot-starter-oauth2-resource-server`), sin gestionar credenciales.
- Autorización:
  - `@PreAuthorize("hasRole('USER')")` o `hasRole('ADMIN')` a nivel de método.
  - Ownership: se compara `jwt.getSubject()` contra `account.ownerId` en el service layer (no en el controller) para operaciones sobre cuentas propias.
- `auth-service` es el único punto que habla con la **Admin API de Keycloak** para registro de usuarios; los demás servicios solo validan tokens.

## Comunicación entre servicios

- **Síncrona (REST)**: `gateway` → cualquier servicio (enrutamiento de cliente externo). `auth-service` → Keycloak Admin API.
- **Asíncrona (Kafka)**: `transfer-service` ↔ `account-service` para la saga de transferencia (ver sección anterior).
- **Service discovery**: todos los servicios (excepto `eureka-server` mismo) se registran en Eureka; `gateway` resuelve rutas vía Eureka en vez de URLs fijas.

## Infraestructura (Docker Compose)

| Componente | Rol |
|---|---|
| MongoDB (replica set 1 nodo) | Persistencia de `account-service` y `transfer-service` (bases lógicas separadas) |
| Keycloak (`start-dev`, realm importado) | Identity Provider |
| Kafka (KRaft, sin Zookeeper) | Bus de eventos de la saga |
| Eureka Server (módulo propio) | Service discovery |

## Estructura del repositorio

Monorepo Maven multi-módulo:

```
API_Fidelizacion/
├── pom.xml                # parent POM
├── docker-compose.yml
├── docker/                # scripts de init (mongo, keycloak realm, kafka topics)
├── eureka-server/
├── account-service/
├── transfer-service/
├── auth-service/
├── gateway/
└── docs/
    ├── FUNCIONAL.md
    ├── ARQUITECTURA.md
    └── superpowers/specs/   # specs detalladas por fase
```

## Testing

- Cobertura total: unitarias (JUnit 5 + Mockito), integración (`@WebMvcTest`/`@SpringBootTest`), y end-to-end cross-servicio.
- **Testcontainers** para pruebas de integración contra Mongo (replica set real) y Kafka reales — no mocks ni bases embebidas.
- Fase 5 dedicada exclusivamente a testing end-to-end del flujo completo (registro → creación de cuenta → transferencia → saga → consulta de historial).

## Fases de entrega (arquitectura)

1. **Fase 0 — Infraestructura base**: Mongo, Keycloak, Kafka, Eureka levantando sanos vía Docker Compose, módulos placeholder creados. *(spec: `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase0-infra-design.md`)*
2. **Fase 1 — account-service**: CRUD, saldo, bloqueo, consumidor de eventos de saga.
3. **Fase 2 — transfer-service**: endpoint `/transfer`, productor/consumidor de eventos, lógica de saga y compensación.
4. **Fase 3 — auth-service**: fachada de registro sobre Keycloak Admin API.
5. **Fase 4 — gateway**: enrutamiento único vía Eureka.
6. **Fase 5 — Testing e2e**: Testcontainers cross-servicio, flujo completo.

## Riesgos conocidos

- Saga coreografiada con Kafka para dos cuentas que viven en el mismo servicio es sobre-ingeniería intencional (fin de práctica), no la solución más simple posible.
- Keycloak en `start-dev` y client secret hardcodeado: aceptable solo para entorno de práctica/desarrollo, no producción.
- Sin distributed tracing (Zipkin/Sleuth) en el alcance actual — se podría agregar en una fase futura si se quiere observabilidad end-to-end de la saga.
