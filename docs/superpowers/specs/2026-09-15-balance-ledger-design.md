# Balance Ledger (Kardex de auditoría) — Documento de Diseño

## Contexto

Hoy `Account` (colección `accounts` en `account-service`) solo guarda el saldo actual (`balance`) y cuándo fue la última modificación (`updatedAt`) — no existe un historial de movimientos por cuenta. `GET /accounts/{id}/transactions` (delegado a `transfer-service`) solo cubre transferencias entre cuentas; no cubre la creación de una cuenta con saldo inicial (hoy se asigna directo, sin dejar rastro de origen) ni ningún ajuste administrativo futuro.

Esta spec agrega un **kardex de auditoría real**: cada movimiento de saldo, sin importar su origen, queda registrado como una línea inmutable con saldo antes/después. Para que ese registro sea contablemente correcto (nunca "aparece" saldo de la nada), toda inyección inicial de puntos deja de ser un valor arbitrario en `POST /accounts` y pasa a ser una transferencia real desde una cuenta de tesorería del sistema.

## Decisiones de diseño (con alternativas descartadas)

### D1 — Cuenta de tesorería (`acc-treasury`)
**Decisión:** se auto-crea (seed) al arrancar `account-service` si no existe — mismo patrón que los usuarios de prueba de Keycloak (Fase 0). No existe un endpoint público para crearla.
**Alternativa descartada:** creación manual vía `POST /accounts` por un `ADMIN` la primera vez — se descarta porque depende de un paso manual que, si se olvida, rompe todo el flujo de fondeo de cuentas nuevas en cualquier entorno (desarrollo, CI, producción).
**Solo un `ADMIN` puede transferir desde `acc-treasury`** — la transferencia normal ya valida ownership (`jwt.sub == account.ownerId`); como `acc-treasury` no tiene un dueño humano, la única forma de operar sobre ella es con rol `ADMIN` (que ya salta esa validación en el resto de endpoints, ver `FUNCIONAL.md`).

### D2 — Saldo de la tesorería sin límite inferior
**Decisión:** `acc-treasury` puede quedar en negativo — representa que el sistema emite puntos según necesidad de negocio, no un pool físico finito.
**Alternativa descartada:** saldo inicial finito que se agota como cualquier cuenta — más estricto contablemente, pero agrega un paso operativo de reabastecimiento manual que nadie pidió resolver todavía.
**Implicación técnica:** la operación atómica de débito (`debitIfSufficientBalance`) no puede aplicarse tal cual a `acc-treasury` — necesita una variante que no valide `balance >= amount` para esta cuenta específica (ver Task de implementación).

### D3 — `POST /accounts` ya no acepta saldo inicial
**Decisión:** toda cuenta nueva se crea con `balance = 0`. Cualquier fondeo inicial es una transferencia real y explícita desde `acc-treasury` (`POST /transfer`, requiere `ADMIN`), quedando registrada igual que cualquier otra transferencia (en `transactions` de `transfer-service` y en el `balance_ledger` de ambas cuentas).
**Alternativa descartada:** mantener el parámetro `balance` en `POST /accounts` pero traducirlo internamente a una transferencia desde tesorería — más simple para quien llama al endpoint (no tiene que hacer 2 llamadas), pero mezcla dos responsabilidades en un solo endpoint (crear cuenta ≠ fondearla) y oculta el hecho de que fondear requiere `ADMIN`, generando una inconsistencia de permisos confusa (¿por qué crear cuenta con saldo requeriría admin y crear cuenta sin saldo no?).
**Efecto en el contrato de la API:** `CreateAccountRequest` (`account-service`, Fase 1) pierde el campo `balance`; la cuenta creada siempre inicia `ACTIVE` con `balance: 0`.

### D4 — Consistencia del ledger: transacción multi-documento de Mongo
**Decisión:** cada operación atómica de saldo (débito, crédito, compensación) escribe el cambio de `balance` **y** la línea del `balance_ledger` dentro de una única transacción de MongoDB (`ClientSession` con `startTransaction()`/`commitTransaction()`), usando el replica-set de 1 nodo ya existente desde Fase 0 (requisito de Mongo para transacciones multi-documento).
**Alternativa descartada:** escribir el ledger justo después del `$inc`, sin transacción — más simple, pero deja una ventana (rara pero real) donde el proceso muere entre los dos writes y el saldo queda desincronizado del historial que se supone lo audita — inaceptable para una pieza cuyo único propósito es ser confiable para auditoría.

### D5 — Sin campos duplicados entre colecciones (no relacional, por diseño)
No hay claves foráneas en MongoDB — la integridad se sostiene por **convención de referencia + una sola fuente de verdad por dato**, no por constraints de base de datos. Reglas:

- `balance_ledger` es la **única** fuente de verdad del historial de saldo de una cuenta. `Account.balance` sigue siendo la fuente de verdad del **saldo actual** (se recalcula/mantiene por el mismo `$inc`, no se deriva del ledger en cada lectura — evita tener que sumar todo el historial para responder `GET /accounts/{id}`).
- `balance_ledger` **no duplica** `amount`/`status` de `Transaction` (que vive en `transfer-service`, otra base de datos). Cada línea del ledger referencia la transacción de origen por `transactionId` (string, nullable) — un simple puntero, no una copia de sus datos. Si se necesita el detalle completo de una transferencia, se consulta `transfer-service` con ese ID (mismo patrón ya usado por `GET /accounts/{id}/transactions`, que delega a `transfer-service` vía `TransferClient`).
- `Transaction` (`transfer-service`) **no** guarda saldos — sigue siendo únicamente la máquina de estados de la saga (`PENDING`/`COMPLETED`/`FAILED`). Los saldos antes/después son un dato de `account-service` (dueño de `Account`), no de `transfer-service`.
- Esto significa que la trazabilidad completa de una transferencia queda repartida (por diseño, en un sistema de microservicios sin BD compartida): el *qué se intentó y en qué terminó* vive en `transfer-service.transactions`; el *efecto exacto en cada cuenta* vive en `account-service.balance_ledger`, correlacionados por `transactionId`.

### D6 — Modelo del documento `BalanceLedgerEntry`

```json
{
  "_id": "<UUID>",
  "accountId": "acc-xxxx",
  "eventType": "SEED | DEBIT | CREDIT | COMPENSATION",
  "transactionId": "<UUID o null>",
  "delta": -40,
  "balanceBefore": 100,
  "balanceAfter": 60,
  "createdAt": "2026-09-15T10:00:00Z"
}
```

| Campo | Tipo | Notas |
|---|---|---|
| `_id` | String (UUID) | Identificador propio de la línea, no correlativo con nada más. |
| `accountId` | String | Referencia a `Account._id` (misma base de datos, `account-service`) — sin FK, validación de existencia ya garantizada porque solo se escribe dentro de la misma transacción que modifica esa cuenta. |
| `eventType` | Enum | `SEED` (fondeo inicial desde tesorería), `DEBIT`, `CREDIT` (mitades de una transferencia), `COMPENSATION` (reversión). |
| `transactionId` | String, nullable | Referencia cruzada a `transfer-service.transactions._id`. Presente en `DEBIT`/`CREDIT`/`COMPENSATION` (todo lo que se origina en una saga); en la práctica `SEED` también se origina en una transferencia real (D3), así que también lleva `transactionId` — el campo solo sería `null` si en el futuro se agrega algún ajuste administrativo directo fuera del flujo de transferencias (fuera de alcance actual). |
| `delta` | long | Positivo o negativo; `balanceAfter - balanceBefore`, guardado explícito para no obligar a quien lea el ledger a restar. |
| `balanceBefore` / `balanceAfter` | long | Capturados dentro de la misma operación atómica que aplica el cambio (ver D4) — nunca de una lectura separada. |
| `createdAt` | Instant | Timestamp de la línea. |

## Diagrama de relaciones (no relacional — por referencia, no por FK)

```
account-service (BD: account_service)
├── accounts            { _id: "acc-xxxx", ownerId, balance, status, ... }
└── balance_ledger       { _id, accountId → accounts._id (misma BD),
                            transactionId → transfer_service.transactions._id (otra BD, otro servicio) }

transfer-service (BD: transfer_service)
└── transactions         { _id, sourceAccountId → account_service.accounts._id (otra BD),
                            targetAccountId → account_service.accounts._id (otra BD),
                            amount, status, failureReason, ... }
```

Ningún campo se repite con el mismo propósito en dos colecciones: `balance_ledger` no tiene `amount`/`status` de la transferencia (los referencia por ID), `transactions` no tiene `balance`/`balanceBefore`/`balanceAfter` (eso vive solo en `balance_ledger`). Las referencias cruzadas de servicio (`accountId` desde `transactions`, `transactionId` desde `balance_ledger`) son punteros de solo lectura — ninguno de los dos servicios puede hacer un `JOIN` real (no existe en Mongo), así que cualquier consulta que necesite ambos lados (ej. "dame la transferencia completa con sus saldos") requiere 2 llamadas — igual que ya lo hace hoy `GET /accounts/{id}/transactions` combinando datos propios con una llamada a `transfer-service`.

## Cambios de contrato

- `CreateAccountRequest` (`account-service`): se elimina el campo `balance` (D3). `POST /accounts` siempre crea con `balance: 0`.
- `AccountAtomicOperations` (`account-service`): se agregan `debitFromTreasury(long amount)` (sin validar suficiencia de saldo, D2) y se modifican `debitIfSufficientBalance`/`creditIfActive`/`creditUnconditionally` para, dentro de la misma transacción Mongo, insertar la línea correspondiente en `balance_ledger`.
- Nuevo endpoint de solo lectura: `GET /accounts/{id}/ledger` — lista el kardex de una cuenta (mismo control de autorización que `GET /accounts/{id}` — dueño o `ADMIN`).
- El seed de `acc-treasury` se agrega al arranque de `account-service` (similar a como Fase 0 sembró los usuarios de prueba de Keycloak, pero aquí es código de aplicación — un `CommandLineRunner` o `ApplicationRunner` que crea la cuenta si no existe, no un archivo de config externo).

## Fuera de alcance de esta spec

- Reabastecer o poner límite a `acc-treasury` (D2, decisión explícita de no hacerlo ahora).
- Ajustes administrativos de saldo fuera del flujo de transferencias (ej. "corrección manual" de un `ADMIN` sin pasar por `POST /transfer`) — si se necesita en el futuro, el modelo de `BalanceLedgerEntry` ya lo soporta (`transactionId: null`), pero no se implementa un endpoint para eso ahora.
- Migración de cuentas ya existentes en un entorno con datos previos (esta spec asume que se implementa antes de tener cuentas reales en producción, o que un entorno con datos previos acepta que su ledger histórico empieza vacío desde el momento del despliegue).
