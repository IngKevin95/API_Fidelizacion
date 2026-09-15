# Fase 0 — Infraestructura base: Loyalty Microservice Platform

## Contexto

El reto técnico original ("Servicio de Transferencia de Puntos") se expandió, por decisión del usuario, de un único endpoint monolítico a una plataforma de microservicios con fines de práctica personal (MongoDB, Keycloak, Kafka, Eureka, Spring Cloud Gateway). Dado el tamaño resultante, el proyecto se descompuso en fases independientes, cada una con su propio ciclo spec → plan → implementación:

1. **Fase 0 — Infraestructura base** (esta spec)
2. Fase 1 — account-service
3. Fase 2 — transfer-service (saga coreografiada)
4. Fase 3 — auth-service
5. Fase 4 — Gateway
6. Fase 5 — Testing e2e cross-servicio

Esta spec cubre **solo la Fase 0**: levantar la infraestructura compartida sin código de negocio, de forma que las fases siguientes construyan sobre una base verificablemente sana.

## Objetivo

`docker-compose up` levanta 4 piezas de infraestructura (MongoDB, Keycloak, Kafka, Eureka Server) totalmente configuradas y saludables, sin necesidad de pasos manuales adicionales.

## Decisiones de arquitectura

| Decisión | Elección | Razón |
|---|---|---|
| Persistencia | MongoDB (no Postgres/JPA pese a que el enunciado original lo sugiere) | Objetivo explícito de práctica personal con Mongo |
| Modo Mongo | Replica set de 1 nodo (`rs0`) | Transacciones ACID multi-documento en Mongo requieren replica set; con 1 nodo basta para desarrollo/práctica |
| Concurrencia en transferencias | Update atómico condicional (`findOneAndUpdate` con filtro `balance >= amount`), no locking pesimista relacional | Equivalente Mongo-idiomático a `SELECT FOR UPDATE`; se implementará en Fase 1/2, no aquí |
| Identity Provider | Keycloak (Resource Server pattern) | Decisión del usuario sobre OAuth2 real en vez de JWT casero |
| Broker de eventos | Kafka en modo KRaft (sin Zookeeper) | Estándar actual, evita complejidad extra de Zookeeper |
| Service discovery | Eureka Server (módulo propio, sin imagen oficial reusable) | Elegido explícitamente por el usuario junto con Spring Cloud Gateway |
| Estructura de repo | Monorepo Maven multi-módulo | Un solo repo para todo el sistema, más simple de versionar y entregar |
| IDs de negocio | Strings tipo `acc-xxxx` como `_id` de Mongo (no ObjectId nativo) | Compatibilidad con el formato de ejemplo del enunciado original y para no exponer ObjectId en la API |

## Estructura del repositorio

```
API_Fidelizacion/
├── pom.xml                       # parent POM (packaging=pom, módulos hijos)
├── docker-compose.yml
├── docker/
│   ├── mongo/
│   │   └── init-replica.js       # script rs.initiate()
│   ├── keycloak/
│   │   └── loyalty-realm.json    # realm export a importar
│   └── kafka/
│       └── (config KRaft, si aplica por env vars en compose)
├── eureka-server/                # único módulo con código en esta fase
│   ├── pom.xml
│   └── src/main/java/.../EurekaServerApplication.java
├── account-service/               # placeholder de módulo (Fase 1)
├── transfer-service/               # placeholder de módulo (Fase 2)
├── auth-service/                    # placeholder de módulo (Fase 3)
├── gateway/                          # placeholder de módulo (Fase 4)
└── docs/superpowers/specs/
```

Los módulos `account-service`, `transfer-service`, `auth-service`, `gateway` se crean en esta fase solo como **placeholders vacíos** (POM mínimo, sin código) para que el parent POM multi-módulo compile desde ya; su contenido real se construye en fases posteriores.

## Componentes de infraestructura

### 1. MongoDB (replica set de 1 nodo)
- Imagen oficial `mongo`, arrancada con `--replSet rs0`.
- Contenedor auxiliar (o `entrypoint`/`healthcheck` con `mongosh`) ejecuta `rs.initiate()` una sola vez tras el primer arranque.
- Puerto expuesto: `27017`.
- Healthcheck: `mongosh --eval "rs.status().ok"` debe devolver `1`.

### 2. Keycloak
- Imagen oficial `quay.io/keycloak/keycloak`.
- Modo `start-dev` (suficiente para entorno de práctica; no producción).
- Import automático de `loyalty-realm.json` al arranque (`--import-realm`), que define:
  - Realm `loyalty-realm`.
  - Roles: `USER`, `ADMIN`.
  - Client `loyalty-app` (confidential, con client secret fijo para desarrollo).
  - Un usuario de prueba con rol `USER` y otro con `ADMIN` (para poder probar sin pasar por un flujo de registro que aún no existe).
- Puerto expuesto: `8080` (mapeado a un puerto distinto en el host si choca con otro servicio, ej. `8180:8080`).
- Healthcheck: endpoint `/health/ready` responde `200`.

### 3. Kafka (KRaft)
- Imagen `apache/kafka` (o `bitnami/kafka` en modo KRaft) — sin Zookeeper.
- Topics pre-creados al arranque vía script o `KAFKA_CREATE_TOPICS`:
  - `debit-events`
  - `credit-events`
  - `transfer-compensation`
- Puerto expuesto: `9092`.
- Healthcheck: `kafka-topics.sh --list` debe listar los 3 topics.

### 4. Eureka Server
- Único componente con código Java real en esta fase: proyecto Spring Boot mínimo con `spring-cloud-starter-netflix-eureka-server` y `@EnableEurekaServer`.
- Corre como contenedor propio construido desde su Dockerfile (no imagen de terceros).
- Puerto expuesto: `8761`.
- Healthcheck: `GET /actuator/health` → `UP`.

## docker-compose.yml (resumen de servicios)

```yaml
services:
  mongo:        # replica set 1 nodo + init
  keycloak:     # realm import
  kafka:        # KRaft, topics pre-creados
  eureka-server: # build desde ./eureka-server
```

Todos los servicios declaran `healthcheck` y `depends_on: condition: service_healthy` donde aplique, de forma que `docker-compose up` no se considere "arriba" hasta que las 4 piezas respondan sanas.

## Criterios de aceptación (Fase 0)

1. `docker-compose up` levanta los 4 servicios sin intervención manual.
2. `mongosh` contra el contenedor confirma `rs.status().ok == 1`.
3. Keycloak expone el realm `loyalty-realm` con roles y client ya creados (verificable vía Admin Console o API REST de Keycloak).
4. Kafka lista los 3 topics esperados.
5. Eureka Server responde `UP` en `/actuator/health` y su dashboard en `:8761` carga (sin clientes registrados aún, eso es normal en esta fase).
6. El `pom.xml` padre compila (`mvn install`) incluyendo los módulos placeholder vacíos.

## Fuera de alcance (explícitamente, para esta fase)

- Cualquier lógica de negocio (Account, Transaction, Transfer).
- Registro/login real de usuarios (los usuarios de prueba se precargan en el realm importado).
- Gateway enrutando tráfico (no existe aún tráfico de negocio que enrutar).
- Tests automatizados de negocio (Fase 5). En esta fase, la única "prueba" es la verificación manual de los criterios de aceptación arriba.

## Riesgos / notas

- Keycloak en modo `start-dev` no es apto para producción; para este ejercicio de práctica es aceptable y se documenta como tal.
- El client secret de Keycloak queda hardcodeado en el realm JSON para desarrollo; en una fase posterior de hardening se movería a secretos gestionados (fuera de alcance del ejercicio).
- Eureka Server sin autenticación propia (dashboard abierto); aceptable en entorno local/práctica.
