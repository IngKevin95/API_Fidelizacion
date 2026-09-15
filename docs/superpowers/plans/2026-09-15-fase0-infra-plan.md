# Fase 0 — Infraestructura base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Levantar, con un solo `docker-compose up`, la infraestructura compartida del sistema (MongoDB replica-set, Keycloak con realm importado, Kafka en modo KRaft, Eureka Server) y el esqueleto de repositorio Maven multi-módulo, sin ningún código de negocio.

**Architecture:** Monorepo Maven multi-módulo. Un único módulo con código real (`eureka-server`, Spring Boot mínimo con `@EnableEurekaServer`); los módulos `account-service`, `transfer-service`, `auth-service`, `gateway` se crean como placeholders vacíos (POM mínimo, sin clases) para que el build padre compile. Los demás componentes (Mongo, Keycloak, Kafka) son contenedores de imágenes oficiales configurados vía Docker Compose y scripts de inicialización.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Cloud Netflix Eureka Server, Maven multi-módulo, Docker Compose, MongoDB (replica-set 1 nodo), Keycloak (`start-dev`), Apache Kafka (KRaft).

**Spec:** `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase0-infra-design.md` (y contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- Build tool: Maven multi-módulo (no Gradle) — decisión D15 de `ARQUITECTURA.md`.
- Java 21 (LTS más reciente compatible con Spring Boot 3.x).
- Ningún servicio de negocio (`account-service`, `transfer-service`, `auth-service`, `gateway`) lleva código en esta fase — solo POM placeholder.
- Todo componente de infraestructura en `docker-compose.yml` declara `healthcheck`; dependencias usan `depends_on: condition: service_healthy`.
- IDs de negocio (fuera de alcance de esta fase, pero relevante para el realm de Keycloak): no aplica aún, no hay usuarios de negocio, solo usuarios de prueba.
- `.gitignore` ya existe en la raíz (`target/`, `.history/`, `*.log`, `.env`) — no recrear, solo extender si hace falta.

---

## File Structure

```
API_Fidelizacion/
├── pom.xml                              # parent POM, packaging=pom, declara los 5 módulos
├── docker-compose.yml                    # orquesta mongo, keycloak, kafka, eureka-server
├── docker/
│   ├── mongo/
│   │   └── init-replica.sh               # inicializa el replica-set rs0
│   ├── keycloak/
│   │   └── loyalty-realm.json            # realm export a importar
│   └── kafka/
│       └── create-topics.sh              # crea los 3 topics al arranque
├── eureka-server/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/com/loyalty/eureka/EurekaServerApplication.java
│       │   └── resources/application.yml
│       └── test/java/com/loyalty/eureka/EurekaServerApplicationTests.java
├── account-service/pom.xml               # placeholder
├── transfer-service/pom.xml              # placeholder
├── auth-service/pom.xml                  # placeholder
├── gateway/pom.xml                       # placeholder
└── docs/                                  # ya existe (FUNCIONAL.md, ARQUITECTURA.md, specs/)
```

---

### Task 1: Parent POM y módulos placeholder

**Files:**
- Create: `pom.xml`
- Create: `account-service/pom.xml`
- Create: `transfer-service/pom.xml`
- Create: `auth-service/pom.xml`
- Create: `gateway/pom.xml`

**Interfaces:**
- Consumes: nada (primer task).
- Produces: coordenadas Maven `com.loyalty:loyalty-microservice-platform:1.0.0-SNAPSHOT` (parent); cada módulo hijo hereda de él con `groupId=com.loyalty`, versión `1.0.0-SNAPSHOT`. `eureka-server` (Task 2) declarará su propio `artifactId=eureka-server` como módulo hijo.

- [ ] **Step 1: Crear el parent POM**

`pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.loyalty</groupId>
  <artifactId>loyalty-microservice-platform</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>3.3.4</spring-boot.version>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <modules>
    <module>eureka-server</module>
    <module>account-service</module>
    <module>transfer-service</module>
    <module>auth-service</module>
    <module>gateway</module>
  </modules>
</project>
```

- [ ] **Step 2: Crear los 4 POM placeholder** (mismo contenido, cambia solo `artifactId`)

`account-service/pom.xml` (repetir para `transfer-service`, `auth-service`, `gateway` cambiando `artifactId`):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.loyalty</groupId>
    <artifactId>loyalty-microservice-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>account-service</artifactId>
  <packaging>jar</packaging>

  <!-- Placeholder para Fase 1. Sin dependencias ni codigo todavia. -->
</project>
```

- [ ] **Step 3: Verificar que el build multi-módulo compila (sin eureka-server aún, se agrega en Task 2)**

Run: `mvn -q validate`
Expected: `BUILD SUCCESS` (o falla mencionando que falta el módulo `eureka-server` — en ese caso, continuar a Task 2 antes de validar el build completo).

- [ ] **Step 4: Commit**

```bash
git add pom.xml account-service/pom.xml transfer-service/pom.xml auth-service/pom.xml gateway/pom.xml
git commit -m "build: crea parent POM y modulos placeholder de account-service, transfer-service, auth-service y gateway"
```

---

### Task 2: eureka-server — aplicación mínima con test de salud

**Files:**
- Create: `eureka-server/pom.xml`
- Create: `eureka-server/src/main/java/com/loyalty/eureka/EurekaServerApplication.java`
- Create: `eureka-server/src/main/resources/application.yml`
- Test: `eureka-server/src/test/java/com/loyalty/eureka/EurekaServerApplicationTests.java`

**Interfaces:**
- Consumes: parent POM de Task 1 (`com.loyalty:loyalty-microservice-platform:1.0.0-SNAPSHOT`).
- Produces: servicio HTTP en el puerto `8761`, endpoint `GET /actuator/health` respondiendo `{"status":"UP"}`. Este es el contrato que Docker Compose (Task 6) usará como `healthcheck`, y que los servicios de negocio de fases futuras usarán como `eureka.client.service-url.defaultZone=http://eureka-server:8761/eureka/`.

- [ ] **Step 1: Escribir el test que falla (aplicación aún no existe)**

`eureka-server/src/test/java/com/loyalty/eureka/EurekaServerApplicationTests.java`:
```java
package com.loyalty.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    void healthEndpointReturnsUp() {
        TestRestTemplate restTemplate = new TestRestTemplate();
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: Crear el POM del módulo (necesario para que el test compile y corra)**

`eureka-server/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.loyalty</groupId>
    <artifactId>loyalty-microservice-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>eureka-server</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Ejecutar el test y confirmar que falla (no hay clase de aplicación todavía)**

Run: `mvn -q -pl eureka-server -am test`
Expected: FAIL — `EurekaServerApplication` no existe / `ApplicationContext` no puede arrancar.

- [ ] **Step 4: Crear la clase de aplicación mínima**

`eureka-server/src/main/java/com/loyalty/eureka/EurekaServerApplication.java`:
```java
package com.loyalty.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

- [ ] **Step 5: Crear la configuración**

`eureka-server/src/main/resources/application.yml`:
```yaml
server:
  port: 8761

eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  server:
    enable-self-preservation: false

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl eureka-server -am test`
Expected: `BUILD SUCCESS`, `healthEndpointReturnsUp` en verde.

- [ ] **Step 7: Verificar que el build completo del monorepo compila (con los 5 módulos)**

Run: `mvn -q clean install`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add eureka-server/
git commit -m "feat: agrega eureka-server minimo con test de healthcheck"
```

---

### Task 3: Dockerfile de eureka-server

**Files:**
- Create: `eureka-server/Dockerfile`

**Interfaces:**
- Consumes: el JAR construido por Task 2 (`eureka-server/target/eureka-server-1.0.0-SNAPSHOT.jar`).
- Produces: imagen Docker `loyalty/eureka-server` que expone el puerto `8761`, consumida por `docker-compose.yml` en Task 6.

- [ ] **Step 1: Crear el Dockerfile multi-stage**

`eureka-server/Dockerfile`:
```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY eureka-server/src eureka-server/src
RUN mvn -q -pl eureka-server -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/eureka-server/target/eureka-server-*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
```

*(Corregido durante la implementación: el repo no tiene wrapper `mvnw`, y el fallback original con `apt-get` fallaba sobre una imagen Alpine, que usa `apk`. Se usa la imagen oficial `maven:3.9-eclipse-temurin-21-alpine`, que trae Maven preinstalado, copiando solo los POMs y el código fuente necesarios para el build en capas cacheables.)*

- [ ] **Step 2: Verificar que la imagen construye localmente**

Run: `docker build -t loyalty/eureka-server -f eureka-server/Dockerfile .`
Expected: build exitoso, imagen `loyalty/eureka-server` listada en `docker images`.

- [ ] **Step 3: Commit**

```bash
git add eureka-server/Dockerfile
git commit -m "build: agrega Dockerfile multi-stage para eureka-server"
```

---

### Task 4: MongoDB replica-set con script de inicialización

**Files:**
- Create: `docker/mongo/init-replica.sh`

**Interfaces:**
- Consumes: nada.
- Produces: contenedor Mongo alcanzable en `mongodb://mongo:27017` (red interna de Compose) con `rs.status().ok == 1`, consumido por `docker-compose.yml` en Task 6 y por las fases 1-2 (persistencia de `account-service`/`transfer-service`).

- [ ] **Step 1: Crear el script de inicialización del replica-set**

`docker/mongo/init-replica.sh`:
```sh
#!/bin/sh
set -e

echo "Esperando a que mongod acepte conexiones..."
until mongosh --host localhost --eval "print('ok')" > /dev/null 2>&1; do
  sleep 1
done

echo "Inicializando replica set rs0..."
mongosh --host localhost --eval '
  try {
    rs.status();
    print("Replica set ya inicializado.");
  } catch (e) {
    rs.initiate({
      _id: "rs0",
      members: [{ _id: 0, host: "mongo:27017" }]
    });
    print("Replica set inicializado.");
  }
'
```

- [ ] **Step 2: Dar permisos de ejecución**

Run: `chmod +x docker/mongo/init-replica.sh`
Expected: sin salida (permiso aplicado).

- [ ] **Step 3: Commit**

```bash
git add docker/mongo/init-replica.sh
git commit -m "build: agrega script de inicializacion del replica-set de mongo"
```

---

### Task 5: Keycloak con realm importado

**Files:**
- Create: `docker/keycloak/loyalty-realm.json`

**Interfaces:**
- Consumes: nada.
- Produces: realm `loyalty-realm` con roles `USER`/`ADMIN`, client `loyalty-app` (confidential) y 2 usuarios de prueba (`test-user` con rol `USER`, `test-admin` con rol `ADMIN`), importado automáticamente al arrancar Keycloak con `--import-realm`. Consumido por `docker-compose.yml` (Task 6) y por todas las fases futuras (Resource Server config apunta a este realm).

- [ ] **Step 1: Crear el realm export**

`docker/keycloak/loyalty-realm.json`:
```json
{
  "realm": "loyalty-realm",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "roles": {
    "realm": [
      { "name": "USER", "description": "Usuario estandar de la plataforma" },
      { "name": "ADMIN", "description": "Administrador de la plataforma" }
    ]
  },
  "clients": [
    {
      "clientId": "loyalty-app",
      "enabled": true,
      "publicClient": false,
      "secret": "loyalty-app-dev-secret",
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": true,
      "serviceAccountsEnabled": true,
      "redirectUris": ["*"],
      "protocol": "openid-connect"
    }
  ],
  "users": [
    {
      "username": "test-user",
      "enabled": true,
      "email": "test-user@loyalty.local",
      "emailVerified": true,
      "credentials": [
        { "type": "password", "value": "TestUser123!", "temporary": false }
      ],
      "realmRoles": ["USER"]
    },
    {
      "username": "test-admin",
      "enabled": true,
      "email": "test-admin@loyalty.local",
      "emailVerified": true,
      "credentials": [
        { "type": "password", "value": "TestAdmin123!", "temporary": false }
      ],
      "realmRoles": ["ADMIN"]
    }
  ]
}
```

- [ ] **Step 2: Commit**

```bash
git add docker/keycloak/loyalty-realm.json
git commit -m "build: agrega realm de keycloak con roles, client y usuarios de prueba"
```

---

### Task 6: Kafka (KRaft) con topics pre-creados

**Files:**
- Create: `docker/kafka/create-topics.sh`

**Interfaces:**
- Consumes: nada.
- Produces: los 3 topics (`debit-events`, `credit-events`, `transfer-compensation`) creados en el broker Kafka accesible en `kafka:9092` (red interna), consumidos por `docker-compose.yml` (Task 7) y por `account-service`/`transfer-service` en fases 1-2 (ver `ARQUITECTURA.md §Esquema de eventos`).

- [ ] **Step 1: Crear el script de creación de topics**

`docker/kafka/create-topics.sh`:
```sh
#!/bin/sh
set -e

echo "Esperando a que Kafka acepte conexiones..."
until kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
  sleep 1
done

for topic in debit-events credit-events transfer-compensation; do
  echo "Creando topic: $topic"
  kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done

echo "Topics creados:"
kafka-topics.sh --bootstrap-server localhost:9092 --list
```

- [ ] **Step 2: Dar permisos de ejecución**

Run: `chmod +x docker/kafka/create-topics.sh`
Expected: sin salida.

- [ ] **Step 3: Commit**

```bash
git add docker/kafka/create-topics.sh
git commit -m "build: agrega script de creacion de topics de kafka"
```

---

### Task 7: docker-compose.yml — ensamblado completo y verificación de criterios de aceptación

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: `eureka-server/Dockerfile` (Task 3), `docker/mongo/init-replica.sh` (Task 4), `docker/keycloak/loyalty-realm.json` (Task 5), `docker/kafka/create-topics.sh` (Task 6).
- Produces: los 4 componentes de infraestructura levantados y saludables con un solo `docker-compose up`, verificable contra los criterios de aceptación de la spec.

- [ ] **Step 1: Crear `docker-compose.yml`**

```yaml
services:
  mongo:
    image: mongo:7
    command: ["--replSet", "rs0", "--bind_ip_all"]
    ports:
      - "27017:27017"
    volumes:
      - ./docker/mongo/init-replica.sh:/docker-entrypoint-initdb.d/init-replica.sh:ro
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
      interval: 5s
      timeout: 5s
      retries: 10

  mongo-init:
    image: mongo:7
    depends_on:
      mongo:
        condition: service_healthy
    volumes:
      - ./docker/mongo/init-replica.sh:/init-replica.sh:ro
    entrypoint: ["sh", "/init-replica.sh"]
    restart: "no"

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8180:8080"
    volumes:
      - ./docker/keycloak/loyalty-realm.json:/opt/keycloak/data/import/loyalty-realm.json:ro
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080"]
      interval: 10s
      timeout: 5s
      retries: 15

  kafka:
    image: apache/kafka:3.7.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 5s
      timeout: 5s
      retries: 10

  kafka-topics-init:
    image: apache/kafka:3.7.0
    depends_on:
      kafka:
        condition: service_healthy
    volumes:
      - ./docker/kafka/create-topics.sh:/create-topics.sh:ro
    entrypoint: ["sh", "/create-topics.sh"]
    restart: "no"

  eureka-server:
    build:
      context: .
      dockerfile: eureka-server/Dockerfile
    ports:
      - "8761:8761"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8761/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 5s
      timeout: 5s
      retries: 10
```

- [ ] **Step 2: Verificar que el stack completo levanta**

Run: `docker-compose up -d`
Expected: los 6 servicios (`mongo`, `mongo-init`, `keycloak`, `kafka`, `kafka-topics-init`, `eureka-server`) inician sin error.

- [ ] **Step 3: Verificar criterio de aceptación — Mongo replica-set sano**

Run: `docker exec -it $(docker-compose ps -q mongo) mongosh --eval "rs.status().ok"`
Expected: `1`

- [ ] **Step 4: Verificar criterio de aceptación — Keycloak realm importado**

Run: `curl -s http://localhost:8180/realms/loyalty-realm | grep -q '"realm":"loyalty-realm"' && echo OK`
Expected: `OK`

- [ ] **Step 5: Verificar criterio de aceptación — Kafka topics creados**

Run: `docker exec -it $(docker-compose ps -q kafka) kafka-topics.sh --bootstrap-server localhost:9092 --list`
Expected: lista incluyendo `debit-events`, `credit-events`, `transfer-compensation`.

- [ ] **Step 6: Verificar criterio de aceptación — Eureka Server saludable**

Run: `curl -s http://localhost:8761/actuator/health`
Expected: `{"status":"UP",...}`

- [ ] **Step 7: Verificar criterio de aceptación — build Maven completo**

Run: `mvn -q clean install`
Expected: `BUILD SUCCESS` (los 5 módulos, incluidos los 4 placeholders).

- [ ] **Step 8: Detener el stack**

Run: `docker-compose down -v`
Expected: contenedores y volúmenes eliminados limpiamente.

- [ ] **Step 9: Commit**

```bash
git add docker-compose.yml
git commit -m "build: agrega docker-compose.yml que orquesta mongo, keycloak, kafka y eureka-server"
```

---

## Self-Review

**1. Spec coverage:**
- Objetivo (`docker-compose up` levanta 4 piezas sanas) → Task 7.
- Persistencia MongoDB replica-set → Task 4 + Task 7 (servicio `mongo`).
- Keycloak con realm/roles/client → Task 5 + Task 7 (servicio `keycloak`).
- Kafka KRaft con 3 topics → Task 6 + Task 7 (servicio `kafka`).
- Eureka Server módulo propio → Task 2, Task 3.
- Estructura de repo (monorepo Maven, módulos placeholder) → Task 1.
- Los 6 criterios de aceptación de la spec → Task 7, Steps 3-7.
- Fuera de alcance de la spec (lógica de negocio, registro real, gateway enrutando, tests de negocio) → correctamente no incluido en este plan.

**2. Placeholder scan:** sin TBD/TODO; todos los pasos tienen contenido ejecutable completo (scripts, YAML, Java, Dockerfile).

**3. Type consistency:** `EurekaServerApplication` (Task 2) es la única clase de aplicación; su puerto (`8761`) y endpoint (`/actuator/health`) se referencian consistentemente en Task 3 (Dockerfile `EXPOSE 8761`) y Task 7 (`docker-compose.yml` healthcheck y `ports`). Los 3 nombres de topics (`debit-events`, `credit-events`, `transfer-compensation`) coinciden exactamente entre Task 6 y `ARQUITECTURA.md §Esquema de eventos`.

## Correcciones descubiertas durante la ejecución (verificación real con Docker)

El plan pasó el self-review documental, pero la verificación end-to-end contra Docker real (Task 7) encontró 4 bugs que el self-review no podía detectar sin ejecutar el stack:

1. **`eureka-server/pom.xml` — plugin sin versión ni goal enlazado.** `spring-boot-maven-plugin` no tenía `<version>` (el parent no es `spring-boot-starter-parent`, así que no hereda el pinning) ni `<executions>` con el goal `repackage`. Resultado: el JAR se empaquetaba sin manifest ejecutable (`no main manifest attribute`) y, tras fijar la ejecución, Maven resolvía por defecto la última versión del plugin (`4.2.0-M1`, milestone) en vez de la 3.3.4 declarada. Fix: `<version>${spring-boot.version}</version>` + `<executions><execution><goals><goal>repackage</goal></goals></execution></executions>`.
2. **`eureka-server/Dockerfile` — build stage no compilaba.** Usaba `./mvnw` (no existe wrapper en el repo) con fallback a `apt-get` sobre una imagen Alpine (que usa `apk`, no `apt`). Fix: cambiar la imagen de build a `maven:3.9-eclipse-temurin-21-alpine` (Maven preinstalado) y copiar explícitamente los POMs del monorepo + el código fuente de `eureka-server`.
3. **`docker/mongo/init-replica.sh` — host de conexión incorrecto.** Usaba `--host localhost`, pero el script corre en un contenedor separado (`mongo-init`) de la red de Compose — `localhost` ahí es el propio contenedor, no `mongo`. Fix: `MONGO_HOST="${MONGO_HOST:-mongo}"` usado en ambas invocaciones de `mongosh`.
4. **`docker/kafka/create-topics.sh` — binario fuera del PATH y host incorrecto.** `kafka-topics.sh` no está en el `PATH` de la imagen `apache/kafka` (solo en `/opt/kafka/bin/`), y el script apuntaba a `localhost:9092` corriendo en el contenedor `kafka-topics-init` separado. Fix: ruta absoluta `/opt/kafka/bin/kafka-topics.sh` + `KAFKA_HOST="${KAFKA_HOST:-kafka}:9092"`.

Los 4 fixes se verificaron re-levantando el stack completo desde cero (`docker-compose down -v` + `up -d --build`) y re-confirmando los 6 criterios de aceptación en verde. Ninguno de estos bugs era detectable sin ejecutar Docker real — quedan como recordatorio de que, para Fases 1-5, el plan debe incluir un paso de verificación contra infraestructura real (Testcontainers) antes de dar una tarea por completa, no solo revisión de código.
