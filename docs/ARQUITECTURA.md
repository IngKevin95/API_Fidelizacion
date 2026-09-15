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

### Esquema de eventos (payloads)

Todos los eventos llevan `transactionId` como clave de partición Kafka (garantiza orden por transacción) y `sourceAccountId`/`targetAccountId` para que `account-service` sepa sobre qué documento operar.

**Topic `debit-events`**
```json
// evento: DebitRequested (producido por transfer-service)
{ "eventType": "DebitRequested", "transactionId": "uuid", "sourceAccountId": "acc-101", "amount": 150, "timestamp": "..." }
```
`account-service` responde publicando en el mismo topic (o uno de resultado dedicado — decisión de implementación en Fase 1/2, ambos válidos):
```json
{ "eventType": "DebitSucceeded", "transactionId": "uuid" }
// o
{ "eventType": "DebitFailed", "transactionId": "uuid", "reason": "INSUFFICIENT_BALANCE" | "SOURCE_INACTIVE" }
```

**Topic `credit-events`**
```json
{ "eventType": "CreditRequested", "transactionId": "uuid", "targetAccountId": "acc-202", "amount": 150, "timestamp": "..." }
```
Respuestas: `CreditSucceeded` / `CreditFailed` con `reason: "TARGET_INACTIVE" | "TARGET_NOT_FOUND"`.

**Topic `transfer-compensation`**
```json
{ "eventType": "CompensateDebit", "transactionId": "uuid", "sourceAccountId": "acc-101", "amount": 150 }
```
`account-service` revierte el débito (crédito de vuelta) y confirma con `CompensationApplied`.

### Idempotencia y consistencia eventual

- Cada evento incluye `transactionId` único; `account-service` debe tratar el procesamiento de un mismo `transactionId` como idempotente (si Kafka reentrega el mensaje por un rebalance, no debe debitar/acreditar dos veces). Se logra guardando `lastProcessedTransactionId` por operación, o verificando el estado antes de aplicar el cambio.
- El sistema es **eventualmente consistente**: entre que `transfer-service` responde `202 Accepted` y la saga se resuelve, el saldo de las cuentas puede no reflejar aún el resultado final. El cliente debe consultar el estado de la `Transaction` para conocer el resultado definitivo.
- Si `account-service` cae después de aplicar un débito pero antes de publicar `DebitSucceeded`, al reiniciar debe re-publicar el evento pendiente (outbox pattern) o, para el alcance de este ejercicio, aceptar que Kafka garantiza at-least-once y que el consumidor de `transfer-service` maneja duplicados por `transactionId`.

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
- Consistencia eventual: existe una ventana de tiempo entre `202 Accepted` y la resolución final de la saga donde el estado no es determinista para el cliente sin hacer polling.

## Decisiones y alternativas descartadas

Registro completo de cada decisión tomada durante el diseño, con su justificación y lo que se descartó. Sirve como bitácora para entender *por qué* la arquitectura terminó así y no de otra forma.

### D1 — Alcance real del ejercicio
**Decisión:** práctica personal, no evaluación técnica externa (EPAM/NEORIS) real.
**Por qué importa:** habilita alejarse del enunciado original (que pedía JPA + Postgres + un único endpoint monolítico) en favor de maximizar aprendizaje sobre Mongo, Keycloak, Kafka y microservicios. Si fuera evaluación real, se habría recomendado ceñirse literalmente al enunciado (Postgres + JPA + monolito) para maximizar el puntaje.

### D2 — Motor de persistencia: MongoDB vs. PostgreSQL/MySQL
**Decisión:** MongoDB.
**Alternativas descartadas:** PostgreSQL (recomendado inicialmente por ceñirse al enunciado, que menciona JPA/`@Transactional`), MySQL.
**Trade-off aceptado:** Mongo exige replica set (no standalone) para transacciones ACID multi-documento, agregando complejidad de infraestructura que Postgres no tendría. Se acepta porque el objetivo es practicar Mongo.

### D3 — Estrategia de concurrencia en operaciones de saldo
**Decisión:** update atómico condicional (`findOneAndUpdate` con filtro `balance >= amount`), equivalente Mongo-idiomático a un lock.
**Alternativas descartadas:**
- *Locking pesimista relacional* (`SELECT ... FOR UPDATE`) — no existe como primitiva en Mongo.
- *Locking optimista con `@Version` + retry* — válido en Mongo también, pero el update condicional atómico es más simple y no requiere lógica de reintento.
- *Sin control explícito* — descartado siempre: bajo `READ_COMMITTED` (o el equivalente en Mongo) permite condiciones de carrera que dejan saldo negativo.

### D4 — Estado de la cuenta origen
**Decisión:** ambas cuentas (origen y destino) deben estar `ACTIVE` para poder transferir.
**Alternativa descartada:** ceñirse literalmente al enunciado, que solo exige estado activo en la cuenta destino.
**Razón:** una cuenta inactiva no debería poder enviar puntos tampoco; es más consistente como regla de negocio real.

### D5 — Creación/inicialización de cuentas
**Decisión:** exponer endpoints completos de gestión de cuentas (`POST/GET/PATCH /accounts`, `GET /accounts/{id}/transactions`).
**Alternativa descartada:** seed script sin endpoint público de creación (más ceñido al enunciado original, que no pedía CRUD de cuentas).
**Razón:** el usuario pidió explícitamente que el sistema sea "completo", incluyendo creación de cuenta y bloqueo.

### D6 — Gestión de usuarios
**Decisión:** sí se requiere capa de usuario con registro/login real (no solo cuentas sin autenticación propia).
**Alternativa descartada:** modelo "solo cuentas", donde el owner se resuelve simplemente comparando el JWT contra `sourceAccountId` sin un sistema de registro dedicado.

### D7 — Mecanismo de autenticación
**Decisión:** OAuth2/Keycloak externo como Identity Provider.
**Alternativa descartada:** JWT propio emitido por el servicio (Spring Security + `jjwt`) — más simple, sin infraestructura adicional, pero no practica un IdP real como Keycloak.

### D8 — Relación usuario–cuenta
**Decisión:** 1 usuario puede tener N cuentas (creadas explícitamente vía `POST /accounts`).
**Alternativa descartada:** 1 usuario = 1 cuenta creada automáticamente al registrarse (más simple, menos modelo/validación).

### D9 — Arquitectura de servicio: monolito modular vs. microservicios
**Decisión:** microservicios separados (`account-service`, `transfer-service`, luego `auth-service`, `gateway`, `eureka-server`).
**Alternativa descartada (era la recomendada):** monolito modular — un solo servicio Spring Boot con paquetes por dominio (`account`, `transfer`, `config`). Se descartó porque el usuario quería practicar el patrón de microservicios y sus problemas asociados (comunicación de red, saga, discovery).
**Consecuencia directa:** la atomicidad de la transferencia ya no puede resolverse con una transacción Mongo local simple — obliga a diseñar una saga (ver D10) y hace que `/transfer` deje de ser síncrono (ver D12 implícita en el cambio de código de respuesta).

### D10 — Patrón de consistencia distribuida: saga orquestada vs. coreografiada
**Decisión:** saga **coreografiada** basada en eventos Kafka.
**Alternativa descartada (era la recomendada):** saga orquestada síncrona vía REST con compensación explícita desde `transfer-service` — más simple de depurar (llamadas REST directas, stack traces claros) pero menos representativa de un sistema event-driven real.
**Trade-off aceptado:** mayor complejidad operativa (necesita broker, manejo de idempotencia, consistencia eventual) a cambio de practicar el patrón de mensajería asíncrona.

### D11 — Broker de mensajería: Kafka vs. RabbitMQ
**Decisión:** Kafka en modo KRaft (sin Zookeeper).
**Alternativa descartada:** RabbitMQ — más simple de operar para un flujo de saga pequeño, pero Kafka es el estándar más demandado en el mercado y el objetivo es practicarlo.

### D12 — Service discovery y punto de entrada
**Decisión:** Eureka Server + Spring Cloud Gateway.
**Alternativa descartada (era la recomendada):** comunicación directa por nombre de servicio en la red de Docker Compose (`http://account-service:8081`), sin gateway ni discovery — suficiente técnicamente para 2-3 servicios, pero no practica el patrón de descubrimiento dinámico ni un punto de entrada único como en un entorno enterprise real.

### D13 — Servicios finales del sistema
**Decisión:** `account-service` + `transfer-service` + `auth-service` propio (fachada de Keycloak), más `gateway` y `eureka-server` como infraestructura de plataforma.
**Alternativa descartada:** solo `account-service` + `transfer-service`, sin `auth-service` dedicado (cada servicio de negocio validaría el JWT directamente sin fachada de registro).

### D14 — Identificadores de negocio
**Decisión:** IDs de negocio propios (`acc-xxxx`) como `_id` de Mongo.
**Alternativa descartada:** `ObjectId` nativo de Mongo expuesto como string en la API — más idiomático de Mongo pero rompe compatibilidad con el formato de ejemplo del enunciado original (`acc-101`).

### D15 — Cobertura de testing
**Decisión:** cobertura total (unitarias, integración, end-to-end) con Testcontainers contra Mongo y Kafka reales.
**Alternativa descartada:** cobertura mínima literal del enunciado original (1 test unitario de saldo insuficiente + 1 test de integración de validación de `amount`).

### D16 — Estructura de repositorio y build tool
**Decisión:** monorepo Maven multi-módulo.
**Alternativas descartadas:** multi-repo (uno por servicio) — más realista en enterprise pero mucho overhead de gestión para este ejercicio; Gradle multi-módulo (recomendado por build más rápido y sintaxis moderna) — descartado a favor de Maven, más común en entornos EPAM/NEORIS legacy y el que el usuario ya conoce.
