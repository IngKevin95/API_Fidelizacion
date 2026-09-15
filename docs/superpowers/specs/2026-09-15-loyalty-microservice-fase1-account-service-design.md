# Fase 1 — account-service

## Contexto

Segunda fase del sistema Loyalty Microservice Platform (ver `docs/FUNCIONAL.md` y `docs/ARQUITECTURA.md`). Depende de que la Fase 0 (infraestructura) esté operativa: MongoDB replica-set, Keycloak con realm importado, Kafka con topics creados, Eureka Server corriendo.

Esta fase construye `account-service`: el microservicio dueño de la entidad `Account`, responsable de su ciclo de vida completo (creación, consulta, bloqueo/activación, historial) y de aplicar los cambios de saldo que le pide `transfer-service` vía eventos Kafka durante la saga de transferencia.

## Objetivo

`account-service` expone los 4 endpoints REST de gestión de cuentas (protegidos por JWT de Keycloak), persiste en MongoDB, se registra en Eureka, y consume/produce los eventos Kafka de la saga de débito/crédito/compensación definidos en `ARQUITECTURA.md §Esquema de eventos`.

## Alcance funcional (referencia `FUNCIONAL.md §2`)

| Endpoint | Método | Autorización |
|---|---|---|
| `/accounts` | `POST` | `USER` autenticado |
| `/accounts/{id}` | `GET` | Dueño o `ADMIN` |
| `/accounts/{id}/status` | `PATCH` | Solo `ADMIN` |
| `/accounts/{id}/transactions` | `GET` | Dueño o `ADMIN` |

## Modelo de datos

Colección Mongo `accounts`, documento por cuenta (ver `FUNCIONAL.md §Entidades de negocio` para el detalle de campos). `_id` = `acc-xxxx` generado al crear (UUID corto con prefijo `acc-`).

Además, `account-service` necesita persistir el historial de transacciones **relevantes para sus consultas** (`GET /accounts/{id}/transactions`). Dos opciones de diseño posibles aquí — se resuelve en el plan de implementación, no en esta spec — pero la más simple es que `account-service` también consuma (solo lectura, para índice de consulta) el resultado final de cada `Transaction` desde `transfer-service` vía un evento adicional `TransferCompleted`/`TransferFailed`, o que `GET /accounts/{id}/transactions` delegue internamente (server-side) a `transfer-service` vía llamada REST resuelta por Eureka. Se recomienda la segunda opción (delegación REST) para no duplicar la fuente de verdad de `Transaction`, que pertenece a `transfer-service`.

## Capas (Controller / Service / Repository)

- **Controller**: solo orquestación HTTP — mapea DTO ↔ dominio, delega al service, traduce excepciones de negocio a códigos HTTP vía `@RestControllerAdvice`.
- **Service**: lógica pura de negocio (creación, validación de ownership, cambio de estado, aplicación atómica de débito/crédito), sin conocimiento de HTTP ni de Kafka directamente (el listener Kafka traduce evento → llamada al service).
- **Repository**: interfaz Spring Data MongoDB (`AccountRepository extends MongoRepository<Account, String>`), más métodos custom para el update atómico condicional.

## Reglas de negocio a implementar

1. **Creación** (`POST /accounts`): `ownerId = jwt.sub`, `status = ACTIVE`, `balance` = valor del body o `0` por defecto (no negativo — `@PositiveOrZero`).
2. **Consulta** (`GET /accounts/{id}`): `403 Forbidden` si `jwt.sub != account.ownerId` y el usuario no tiene rol `ADMIN`. `404 Not Found` si no existe.
3. **Cambio de estado** (`PATCH /accounts/{id}/status`): solo `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`). Body `{ "status": "ACTIVE" | "INACTIVE" }`.
4. **Débito atómico** (consumidor de `debit-events`, evento `DebitRequested`): `findOneAndUpdate` con filtro `{ _id: sourceAccountId, status: 'ACTIVE', balance: { $gte: amount } }`, update `{ $inc: { balance: -amount } }`. Si no matchea, publicar `DebitFailed` con `reason` apropiado (`INSUFFICIENT_BALANCE` o `SOURCE_INACTIVE` — se puede diferenciar con una lectura previa solo para el motivo, ya que el update en sí es atómico y no distingue la causa del no-match). Si matchea, publicar `DebitSucceeded`.
5. **Crédito atómico** (consumidor de `credit-events`, evento `CreditRequested`): `findOneAndUpdate` con filtro `{ _id: targetAccountId, status: 'ACTIVE' }`, update `{ $inc: { balance: amount } }`. Si no matchea, publicar `CreditFailed` con `reason: TARGET_INACTIVE | TARGET_NOT_FOUND`. Si matchea, publicar `CreditSucceeded`.
6. **Compensación** (consumidor de `transfer-compensation`, evento `CompensateDebit`): `findOneAndUpdate` incondicional sobre `sourceAccountId` con `{ $inc: { balance: amount } }` (revertir el débito ya aplicado). Publicar `CompensationApplied`.
7. **Idempotencia**: antes de aplicar cualquiera de los pasos 4-6, verificar que `transactionId` no fue ya procesado (colección auxiliar `processed_events` con índice único sobre `transactionId + eventType`, o campo `lastProcessedTransactionId` en el propio documento de cuenta — a decidir en el plan).

## Seguridad

- `account-service` es OAuth2 Resource Server: `spring-boot-starter-oauth2-resource-server` + configuración `issuer-uri` apuntando al realm `loyalty-realm` de Keycloak.
- Mapeo de roles: el JWT de Keycloak trae los roles en `realm_access.roles`; se necesita un `JwtAuthenticationConverter` custom para que Spring Security los reconozca como `ROLE_USER`/`ROLE_ADMIN`.
- Ownership se valida en el **service layer**, no en el controller (regla de capas del sistema).

## Manejo de errores

- `GlobalExceptionHandler` (`@RestControllerAdvice`) compartido en formato: `{ "code": "ACCOUNT_NOT_FOUND", "message": "...", "timestamp": "..." }`.
- Excepciones de dominio propias: `AccountNotFoundException` → `404`, `AccountAccessDeniedException` → `403`, `InvalidAccountStateException` → `422` (si aplica en validaciones síncronas futuras).

## Testing

- Unit tests (JUnit 5 + Mockito) del `AccountService`: creación, ownership al consultar, cambio de estado, y el caso crítico de débito atómico con saldo insuficiente (no debita).
- Integration tests (`@SpringBootTest` + Testcontainers Mongo real en replica-set) de los 4 endpoints, incluyendo casos `401`/`403` sin token o con token de otro dueño.
- Test de idempotencia: reenviar el mismo evento `DebitRequested` dos veces no debita dos veces.

## Criterios de aceptación

1. Los 4 endpoints responden según la matriz de autorización de `FUNCIONAL.md`.
2. Un `findOneAndUpdate` con dos requests concurrentes de débito sobre la misma cuenta con saldo justo para una sola operación nunca deja el saldo negativo (test de concurrencia).
3. `account-service` aparece registrado en el dashboard de Eureka.
4. Los eventos de resultado (`DebitSucceeded/Failed`, `CreditSucceeded/Failed`, `CompensationApplied`) se publican correctamente y son verificables en un consumidor de prueba.

## Fuera de alcance de esta fase

- `transfer-service` (Fase 2) — esta fase asume que los eventos `DebitRequested`/`CreditRequested`/`CompensateDebit` llegan correctamente formados; no se prueba la orquestación completa de la saga hasta la Fase 5 (e2e).
- Autenticación real de usuarios (`auth-service`, Fase 3) — para probar esta fase se usan los usuarios de prueba precargados en el realm de Keycloak (Fase 0).
