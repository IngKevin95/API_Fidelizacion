# Fase 2 — transfer-service

## Contexto

Tercera fase del sistema Loyalty Microservice Platform (ver `docs/FUNCIONAL.md` y `docs/ARQUITECTURA.md`). Depende de la Fase 0 (infraestructura) y de la Fase 1 (`account-service` capaz de consumir/producir los eventos de la saga).

Esta fase construye `transfer-service`: el microservicio dueño de la entidad `Transaction`, responsable de exponer `POST /api/v1/points/transfer`, validar las reglas de negocio previas, y orquestar (coreografiar) la saga de débito/crédito/compensación contra `account-service` vía Kafka.

## Objetivo

`transfer-service` expone `POST /api/v1/points/transfer`, valida sincrónicamente lo que puede validar sin consultar el estado transaccional completo, dispara la saga, y expone el historial vía eventos de resultado consumidos de vuelta para actualizar el estado de cada `Transaction`.

## Alcance funcional (referencia `FUNCIONAL.md §1`)

`POST /api/v1/points/transfer` → `202 Accepted` con `{ transactionId, status: "PENDING", createdAt }`.

## Modelo de datos

Colección Mongo `transactions` (ver `FUNCIONAL.md §Entidades de negocio` para el detalle completo de campos: `id`, `sourceAccountId`, `targetAccountId`, `amount`, `status`, `failureReason`, `createdAt`, `completedAt`).

## Capas (Controller / Service / Repository)

- **Controller** (`TransferController`): recibe el DTO de request, valida formato vía `jakarta.validation` (`@Positive` en `amount`, `@NotBlank` en los IDs de cuenta), delega al service.
- **Service** (`TransferService` / `TransferServiceImpl`): lógica pura de negocio — valida ownership y reglas previas (consultando `account-service` vía REST resuelto por Eureka, solo para el chequeo síncrono inicial de existencia/estado/saldo, **no** como parte de la transacción atómica), persiste `Transaction` en `PENDING`, publica `DebitRequested`. No conoce HTTP.
- **Repository**: interfaz Spring Data MongoDB (`TransactionRepository`).
- **Kafka listeners** (capa separada, `TransferSagaListener`): consumen `DebitSucceeded/Failed`, `CreditSucceeded/Failed`; traducen el evento a una llamada al service para avanzar/cerrar la saga.

## Validación síncrona previa (antes de iniciar la saga)

Para devolver `400`/`403`/`404`/`422` de forma inmediata en los casos evidentes (mejor experiencia que esperar a que la saga falle asíncronamente para un error que ya se puede detectar), `transfer-service` hace una consulta síncrona **de solo lectura** a `account-service` (`GET /accounts/{id}` vía Eureka + `RestClient`/`WebClient`) para:

1. Confirmar que ambas cuentas existen (`404` si no).
2. Confirmar ownership de `sourceAccountId` contra el JWT (`403` si no coincide y no es `ADMIN`).
3. Confirmar estado `ACTIVE` de ambas y saldo suficiente en origen (`422` si no).

**Nota de diseño importante:** esta validación es un chequeo optimista — existe una ventana entre esta lectura y el momento en que `account-service` aplica el `findOneAndUpdate` atómico real durante la saga, en la que el estado pudo cambiar (otra transferencia concurrente consumió el saldo). Por eso el chequeo atómico real vive en `account-service` (Fase 1), y esta validación síncrona es solo una optimización de UX para rechazar rápido los casos obviamente inválidos — el resultado definitivo siempre lo determina la saga.

## Orquestación de la saga (coreografía, ver `ARQUITECTURA.md §Saga`)

1. Validación síncrona (arriba) pasa → persistir `Transaction { status: PENDING }`.
2. Publicar `DebitRequested` a `debit-events`.
3. Listener consume `DebitSucceeded` → publicar `CreditRequested` a `credit-events`.
4. Listener consume `DebitFailed` → `Transaction.status = FAILED`, `failureReason` del evento. Fin (nada que compensar).
5. Listener consume `CreditSucceeded` → `Transaction.status = COMPLETED`, `completedAt = now()`. Fin.
6. Listener consume `CreditFailed` → publicar `CompensateDebit` a `transfer-compensation`.
7. Listener consume `CompensationApplied` → `Transaction.status = FAILED`, `failureReason` del evento de crédito que la originó. Fin.

**Timeout de saga**: si tras un tiempo configurable (ej. 30s) no se recibe respuesta de `account-service` para un paso dado, marcar `Transaction.status = FAILED` con `failureReason: SAGA_TIMEOUT` — evita transacciones eternamente `PENDING` si `account-service` está caído. Implementación: scheduler que revisa transacciones `PENDING` más viejas que el timeout.

## Seguridad

- `transfer-service` es OAuth2 Resource Server, igual que `account-service` (mismo realm `loyalty-realm`).
- `@PreAuthorize("hasRole('USER')")` en el endpoint; la validación fina de ownership contra `sourceAccountId` ocurre en el service layer tras consultar `account-service`.

## Manejo de errores

- Mismo formato estándar de `GlobalExceptionHandler` que `account-service`: `{ "code", "message", "timestamp" }`.
- `400 Bad Request`: `jakarta.validation` (amount ≤ 0, cuentas iguales).
- `404 Not Found`, `403 Forbidden`, `422 Unprocessable Entity`: resultado de la validación síncrona previa descrita arriba.

## Testing

- Unit test (JUnit 5 + Mockito) del `TransferService`: caso de saldo insuficiente detectado en la validación síncrona (mockeando la respuesta de `account-service`) — **este es el test explícitamente pedido en el requerimiento original**.
- Integration test (`@WebMvcTest`/`MockMvc`) del endpoint: validación de `amount <= 0` devuelve `400` — **también explícitamente pedido en el requerimiento original**.
- Test de la máquina de estados de la saga: dado cada evento de entrada (`DebitSucceeded`, `DebitFailed`, `CreditSucceeded`, `CreditFailed`, `CompensationApplied`), verificar que `Transaction` termina en el estado correcto.
- Test de timeout de saga (con Testcontainers Kafka, simulando que `account-service` no responde).

## Criterios de aceptación

1. `POST /transfer` con `amount <= 0` devuelve `400` sin llegar a publicar ningún evento.
2. `POST /transfer` con saldo insuficiente devuelve `422` sin publicar `DebitRequested` (rechazo síncrono).
3. Un flujo completo exitoso (con `account-service` real corriendo, Fase 1 completa) termina con `Transaction.status = COMPLETED` y los saldos correctamente actualizados.
4. Un flujo donde el crédito en destino falla termina con `Transaction.status = FAILED` y el saldo de origen restaurado (compensación aplicada).

## Fuera de alcance de esta fase

- `auth-service` (Fase 3) — se sigue usando los usuarios de prueba del realm de Keycloak.
- Pruebas end-to-end multi-servicio con Testcontainers orquestando ambos servicios a la vez (Fase 5).
