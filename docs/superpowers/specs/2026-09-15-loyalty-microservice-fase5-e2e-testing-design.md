# Fase 5 — Testing end-to-end

## Contexto

Última fase del sistema Loyalty Microservice Platform (ver `docs/FUNCIONAL.md` y `docs/ARQUITECTURA.md`). Depende de que todas las fases anteriores estén completas: infraestructura (Fase 0), `account-service` (Fase 1), `transfer-service` (Fase 2), `auth-service` (Fase 3), `gateway` (Fase 4).

Cada fase anterior ya incluye su propia cobertura de tests unitarios y de integración aislados (por servicio). Esta fase cierra la cobertura total (`FUNCIONAL.md §Requerimientos no funcionales`) con pruebas que ejercitan el sistema completo, cross-servicio, tal como lo usaría un cliente real.

## Objetivo

Un módulo de test (o módulo Maven dedicado `e2e-tests/`) que levanta todo el stack vía Testcontainers (Mongo replica-set, Keycloak, Kafka, y los 5 servicios de la plataforma) y ejercita los flujos funcionales completos descritos en `FUNCIONAL.md`, verificando el comportamiento observable end-to-end — no implementación interna.

## Estrategia técnica

- **Testcontainers Docker Compose module**: reutiliza el `docker-compose.yml` de la Fase 0 (extendido con los servicios de negocio) para levantar el stack completo en cada ejecución de test, garantizando que el entorno de test es fiel al de despliegue real.
- Alternativa más liviana (a decidir en el plan de implementación si el compose completo resulta lento): Testcontainers individuales por dependencia (Mongo, Keycloak, Kafka) + los servicios de negocio corriendo como procesos Spring Boot embebidos (`@SpringBootTest` de cada uno apuntando a los containers).
- Cliente de test: `RestClient`/`WebTestClient` apuntando al `gateway` como único punto de entrada — los tests e2e nunca llaman directamente a un servicio interno, reflejando cómo lo haría un cliente real.

## Escenarios a cubrir

### E2E-1 — Flujo feliz completo
1. `POST /auth/register` crea un usuario.
2. `POST /auth/login` obtiene un JWT.
3. `POST /accounts` (con ese JWT) crea cuenta origen con saldo inicial.
4. `POST /accounts` crea cuenta destino.
5. `POST /api/v1/points/transfer` transfiere puntos de origen a destino.
6. Poll de `GET /accounts/{origen}/transactions` hasta que la transacción aparezca `COMPLETED`.
7. `GET /accounts/{origen}` y `GET /accounts/{destino}` confirman saldos actualizados correctamente.

### E2E-2 — Saldo insuficiente
1. Setup de usuario + cuentas como en E2E-1, pero `amount` mayor al saldo de origen.
2. `POST /transfer` responde `422` (rechazo síncrono) y no se crea ninguna `Transaction` en estado `PENDING` persistente, o si se crea, termina en `FAILED` sin alterar saldos.

### E2E-3 — Compensación (crédito falla tras débito exitoso)
1. Setup de usuario + cuenta origen activa con saldo, cuenta destino que se **desactiva** (`PATCH /accounts/{destino}/status`) justo después de pasar la validación síncrona inicial (simula condición de carrera) — o, más simple de reproducir determinísticamente: usar un mock/fault-injection en el consumidor de `credit-events` de un `account-service` de test para forzar `CreditFailed`.
2. Verificar que la saga compensa: saldo de origen vuelve a su valor original, `Transaction.status = FAILED`.

### E2E-4 — Ownership y autorización
1. Usuario A crea una cuenta.
2. Usuario B (autenticado, distinto JWT) intenta `POST /transfer` usando la cuenta de A como origen → `403 Forbidden`.
3. Un usuario con rol `ADMIN` sí puede operar sobre la cuenta de A (ej. `PATCH /accounts/{id}/status`).

### E2E-5 — Resiliencia ante caída de un servicio
1. Iniciar una transferencia.
2. Detener el contenedor de `account-service` a mitad de la saga (entre `DebitRequested` publicado y `DebitSucceeded` recibido).
3. Reiniciar `account-service`.
4. Confirmar que la saga se resuelve correctamente al reiniciar (Kafka conserva el evento pendiente) — o, si se cumplió el timeout de saga (`transfer-service`, Fase 2), que la transacción queda `FAILED` con `failureReason: SAGA_TIMEOUT` de forma consistente (sin saldo debitado huérfano).

### E2E-6 — Idempotencia
1. Reenviar manualmente (vía productor Kafka de test) el mismo evento `DebitRequested` con un `transactionId` ya procesado.
2. Confirmar que el saldo no se debita dos veces.

## Herramientas

- JUnit 5 + Testcontainers (módulo `testcontainers-mongodb`, `testcontainers-kafka`, imagen de Keycloak).
- `RestAssured` o `WebTestClient` para las llamadas HTTP contra el Gateway.
- Ejecución en CI separada de los tests unitarios/integración por servicio (más lenta, se corre en su propio stage).

## Criterios de aceptación

1. Los 6 escenarios (E2E-1 a E2E-6) pasan de forma reproducible.
2. La suite completa corre en menos de un tiempo razonable para CI (a definir umbral en el plan — orientativo: bajo 10 minutos).
3. Ningún escenario deja el stack en un estado que afecte la siguiente ejecución (limpieza de datos entre tests, o containers frescos por suite).

## Fuera de alcance de esta fase

- Pruebas de carga/performance (throughput de la saga bajo volumen alto) — no forma parte del requerimiento funcional actual, se documenta como posible fase futura si el sistema necesita validarse bajo carga real.
- Pruebas de seguridad ofensiva (pentesting) — fuera del alcance funcional de este documento.
