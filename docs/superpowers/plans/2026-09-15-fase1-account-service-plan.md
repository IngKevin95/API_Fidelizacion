# Fase 1 — account-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir `account-service`: CRUD de cuentas (`POST/GET/PATCH /accounts`, `GET /accounts/{id}/transactions`), aplicación atómica de débito/crédito consumida vía eventos Kafka durante la saga de transferencia, y validación de ownership/roles vía JWT de Keycloak.

**Architecture:** Controller (HTTP) → Service (lógica pura) → Repository (Spring Data MongoDB + operaciones atómicas custom vía `MongoTemplate`). Un listener Kafka separado traduce eventos de la saga (`debit-events`, `credit-events`, `transfer-compensation`) a llamadas al service. Resource Server OAuth2 validando JWT de Keycloak (realm `loyalty-realm`, ya importado en Fase 0).

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Web, Data MongoDB, OAuth2 Resource Server, Kafka, Actuator), Spring Cloud Netflix Eureka Client, JUnit 5 + Mockito, Testcontainers (MongoDB replica-set real), `spring-security-test`.

**Spec:** `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase1-account-service-design.md` (contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- Módulo Maven ya existe como placeholder (`account-service/pom.xml`) heredando del parent (`com.loyalty:loyalty-microservice-platform:1.0.0-SNAPSHOT`) — no crear un nuevo parent, solo agregar dependencias.
- `_id` de `Account` es un String de negocio `acc-xxxx` (no `ObjectId` nativo) — decisión D13 de `ARQUITECTURA.md`.
- Débito/crédito se aplican con `findOneAndUpdate` atómico condicional (`$gte`/filtro de estado), nunca con lectura + escritura separada — decisión D2 de `ARQUITECTURA.md`.
- Ownership (`jwt.sub == account.ownerId`) se valida en el **service layer**, nunca en el controller.
- Formato estándar de error en todo el sistema: `{ "code", "message", "timestamp" }`.
- Topics Kafka ya existen desde Fase 0: `debit-events`, `credit-events`, `transfer-compensation` (broker en `kafka:9092` dentro de Compose, `localhost:9092` expuesto al host).
- Realm Keycloak `loyalty-realm` ya importado desde Fase 0, con roles `USER`/`ADMIN`, client `loyalty-app`, usuarios de prueba `test-user`/`test-admin`.
- Registro en Eureka: `eureka-server` corre en `eureka-server:8761` dentro de Compose (Fase 0).

---

## File Structure

```
account-service/
├── pom.xml                                          # MODIFICAR: agregar dependencias reales
└── src/
    ├── main/
    │   ├── java/com/loyalty/account/
    │   │   ├── AccountServiceApplication.java        # entrypoint
    │   │   ├── domain/
    │   │   │   ├── Account.java                       # documento Mongo
    │   │   │   └── AccountStatus.java                 # enum ACTIVE/INACTIVE
    │   │   ├── repository/
    │   │   │   ├── AccountRepository.java              # Spring Data MongoRepository
    │   │   │   ├── AccountAtomicOperations.java         # interfaz de ops atomicas custom
    │   │   │   └── AccountAtomicOperationsImpl.java     # implementacion con MongoTemplate
    │   │   ├── dto/
    │   │   │   ├── CreateAccountRequest.java
    │   │   │   ├── UpdateStatusRequest.java
    │   │   │   └── AccountResponse.java
    │   │   ├── service/
    │   │   │   ├── AccountService.java                 # interfaz
    │   │   │   └── AccountServiceImpl.java
    │   │   ├── controller/
    │   │   │   └── AccountController.java
    │   │   ├── security/
    │   │   │   └── SecurityConfig.java                  # Resource Server + JwtAuthenticationConverter
    │   │   ├── exception/
    │   │   │   ├── AccountNotFoundException.java
    │   │   │   ├── AccountAccessDeniedException.java
    │   │   │   └── GlobalExceptionHandler.java
    │   │   └── saga/
    │   │       ├── events/
    │   │       │   ├── DebitRequestedEvent.java
    │   │       │   ├── CreditRequestedEvent.java
    │   │       │   ├── CompensateDebitEvent.java
    │   │       │   ├── DebitResultEvent.java             # DebitSucceeded/Failed unificado con campo success
    │   │       │   └── CreditResultEvent.java
    │   │       ├── SagaEventListener.java                 # @KafkaListener de los 3 topics
    │   │       └── SagaEventPublisher.java                # publica eventos de resultado
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/loyalty/account/
            ├── repository/AccountAtomicOperationsIT.java   # Testcontainers Mongo real
            ├── service/AccountServiceTest.java              # Mockito
            ├── controller/AccountControllerIT.java          # @SpringBootTest + Testcontainers + MockMvc
            └── saga/SagaEventListenerIT.java                 # Testcontainers Mongo + embedded Kafka
```

---

### Task 1: Dependencias del módulo y arranque de la aplicación

**Files:**
- Modify: `account-service/pom.xml`
- Create: `account-service/src/main/java/com/loyalty/account/AccountServiceApplication.java`
- Create: `account-service/src/main/resources/application.yml`
- Test: `account-service/src/test/java/com/loyalty/account/AccountServiceApplicationTests.java`

**Interfaces:**
- Consumes: parent POM (`com.loyalty:loyalty-microservice-platform:1.0.0-SNAPSHOT`), `eureka-server` corriendo en `eureka-server:8761` (Fase 0).
- Produces: aplicación Spring Boot arrancable en el puerto `8081`, registrada en Eureka como `account-service`, con `/actuator/health` respondiendo `UP` — contrato que usarán Task 6 (controller) y `docker-compose.yml` en fases futuras.

- [ ] **Step 1: Escribir el test que falla (aplicación aún no existe)**

`account-service/src/test/java/com/loyalty/account/AccountServiceApplicationTests.java`:
```java
package com.loyalty.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Reemplazar `account-service/pom.xml` con las dependencias reales**

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

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mongodb</artifactId>
      <version>1.20.1</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>1.20.1</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

*(Nota: se fija explícitamente `<version>${spring-boot.version}</version>` en `spring-boot-maven-plugin` y su `<executions>` con goal `repackage` — Fase 0 encontró que, sin esto, el plugin resuelve una versión milestone incorrecta y el JAR queda sin manifest ejecutable. Ver `docs/superpowers/plans/2026-09-15-fase0-infra-plan.md §Correcciones descubiertas durante la ejecución`.)*

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test`
Expected: FAIL — `AccountServiceApplication` no existe.

- [ ] **Step 4: Crear la clase de aplicación**

`account-service/src/main/java/com/loyalty/account/AccountServiceApplication.java`:
```java
package com.loyalty.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Crear `application.yml`**

```yaml
server:
  port: 8081

spring:
  application:
    name: account-service
  data:
    mongodb:
      uri: mongodb://mongo:27017/account_service?replicaSet=rs0
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: account-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.loyalty.account.saga.events
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/loyalty-realm

eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test`
Expected: PASS (`contextLoads`).

- [ ] **Step 7: Commit**

```bash
git add account-service/pom.xml account-service/src/main/java/com/loyalty/account/AccountServiceApplication.java account-service/src/main/resources/application.yml account-service/src/test/java/com/loyalty/account/AccountServiceApplicationTests.java
git commit -m "feat: inicializa account-service con dependencias, config y test de contexto"
```

---

### Task 2: Dominio y repositorio Mongo

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/domain/Account.java`
- Create: `account-service/src/main/java/com/loyalty/account/domain/AccountStatus.java`
- Create: `account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java`
- Test: `account-service/src/test/java/com/loyalty/account/repository/AccountRepositoryIT.java`

**Interfaces:**
- Consumes: `AccountServiceApplication` (Task 1) para el contexto Spring.
- Produces: clase `Account` con campos `id: String`, `ownerId: String`, `balance: long`, `status: AccountStatus`, `createdAt: Instant`, `updatedAt: Instant`. `AccountRepository extends MongoRepository<Account, String>`. Usado por Task 3 (DTOs), Task 4 (service) y Task 7 (atomic ops).

- [ ] **Step 1: Escribir el test de integración que falla**

`account-service/src/test/java/com/loyalty/account/repository/AccountRepositoryIT.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
class AccountRepositoryIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void savesAndFindsAccountById() {
        Account account = new Account();
        account.setId("acc-test-1");
        account.setOwnerId("user-1");
        account.setBalance(100L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());

        accountRepository.save(account);

        Optional<Account> found = accountRepository.findById("acc-test-1");
        assertThat(found).isPresent();
        assertThat(found.get().getBalance()).isEqualTo(100L);
        assertThat(found.get().getOwnerId()).isEqualTo("user-1");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountRepositoryIT`
Expected: FAIL — `Account`, `AccountStatus`, `AccountRepository` no existen.

- [ ] **Step 3: Crear `AccountStatus`**

`account-service/src/main/java/com/loyalty/account/domain/AccountStatus.java`:
```java
package com.loyalty.account.domain;

public enum AccountStatus {
    ACTIVE,
    INACTIVE
}
```

- [ ] **Step 4: Crear `Account`**

`account-service/src/main/java/com/loyalty/account/domain/Account.java`:
```java
package com.loyalty.account.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "accounts")
public class Account {

    @Id
    private String id;
    private String ownerId;
    private long balance;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 5: Crear `AccountRepository`**

`account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String> {
}
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountRepositoryIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/domain/ account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java account-service/src/test/java/com/loyalty/account/repository/AccountRepositoryIT.java
git commit -m "feat: agrega dominio Account y repositorio Mongo con test de integracion"
```

---

### Task 3: DTOs con validación

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/dto/CreateAccountRequest.java`
- Create: `account-service/src/main/java/com/loyalty/account/dto/UpdateStatusRequest.java`
- Create: `account-service/src/main/java/com/loyalty/account/dto/AccountResponse.java`
- Test: `account-service/src/test/java/com/loyalty/account/dto/CreateAccountRequestValidationTest.java`

**Interfaces:**
- Consumes: `Account`, `AccountStatus` (Task 2).
- Produces: `CreateAccountRequest{ balance: Long }` (balance opcional, default 0 si null, validado `@PositiveOrZero` cuando presente), `UpdateStatusRequest{ status: AccountStatus }` (`@NotNull`), `AccountResponse{ id, ownerId, balance, status, createdAt, updatedAt }` con factory estático `AccountResponse.from(Account)`. Usados por Task 4 (service) y Task 6 (controller).

- [ ] **Step 1: Escribir el test de validación que falla**

`account-service/src/test/java/com/loyalty/account/dto/CreateAccountRequestValidationTest.java`:
```java
package com.loyalty.account.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateAccountRequestValidationTest {

    private final Validator validator;

    CreateAccountRequestValidationTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void rejectsNegativeBalance() {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setBalance(-10L);

        Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void acceptsNullBalance() {
        CreateAccountRequest request = new CreateAccountRequest();

        Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=CreateAccountRequestValidationTest`
Expected: FAIL — `CreateAccountRequest` no existe.

- [ ] **Step 3: Crear los 3 DTOs**

`account-service/src/main/java/com/loyalty/account/dto/CreateAccountRequest.java`:
```java
package com.loyalty.account.dto;

import jakarta.validation.constraints.PositiveOrZero;

public class CreateAccountRequest {

    @PositiveOrZero
    private Long balance;

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }
}
```

`account-service/src/main/java/com/loyalty/account/dto/UpdateStatusRequest.java`:
```java
package com.loyalty.account.dto;

import com.loyalty.account.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public class UpdateStatusRequest {

    @NotNull
    private AccountStatus status;

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }
}
```

`account-service/src/main/java/com/loyalty/account/dto/AccountResponse.java`:
```java
package com.loyalty.account.dto;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;

import java.time.Instant;

public class AccountResponse {

    private String id;
    private String ownerId;
    private long balance;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public static AccountResponse from(Account account) {
        AccountResponse response = new AccountResponse();
        response.id = account.getId();
        response.ownerId = account.getOwnerId();
        response.balance = account.getBalance();
        response.status = account.getStatus();
        response.createdAt = account.getCreatedAt();
        response.updatedAt = account.getUpdatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=CreateAccountRequestValidationTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/dto/ account-service/src/test/java/com/loyalty/account/dto/
git commit -m "feat: agrega DTOs de account con validacion declarativa"
```

---

### Task 4: Excepciones de dominio y manejador global

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/exception/AccountNotFoundException.java`
- Create: `account-service/src/main/java/com/loyalty/account/exception/AccountAccessDeniedException.java`
- Create: `account-service/src/main/java/com/loyalty/account/exception/GlobalExceptionHandler.java`
- Test: `account-service/src/test/java/com/loyalty/account/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `AccountNotFoundException(String message)` (unchecked), `AccountAccessDeniedException(String message)` (unchecked), `GlobalExceptionHandler` anotado `@RestControllerAdvice` que traduce: `AccountNotFoundException` → `404`, `AccountAccessDeniedException` → `403`, `MethodArgumentNotValidException` → `400`, cuerpo estándar `{ code, message, timestamp }`. Consumido por Task 5 (service lanza las excepciones) y Task 6 (controller expone los endpoints que las disparan).

- [ ] **Step 1: Escribir el test que falla**

`account-service/src/test/java/com/loyalty/account/exception/GlobalExceptionHandlerTest.java`:
```java
package com.loyalty.account.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAccountNotFoundTo404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccountNotFound(new AccountNotFoundException("no existe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "no existe");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void mapsAccountAccessDeniedTo403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccountAccessDeniedException("no autorizado"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "ACCESS_DENIED");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Crear las excepciones**

`account-service/src/main/java/com/loyalty/account/exception/AccountNotFoundException.java`:
```java
package com.loyalty.account.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
```

`account-service/src/main/java/com/loyalty/account/exception/AccountAccessDeniedException.java`:
```java
package com.loyalty.account.exception;

public class AccountAccessDeniedException extends RuntimeException {
    public AccountAccessDeniedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Crear el `GlobalExceptionHandler`**

`account-service/src/main/java/com/loyalty/account/exception/GlobalExceptionHandler.java`:
```java
package com.loyalty.account.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccountAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccountAccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Payload invalido");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(payload);
    }
}
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/exception/ account-service/src/test/java/com/loyalty/account/exception/
git commit -m "feat: agrega excepciones de dominio y manejador global de errores"
```

---

### Task 5: Capa de servicio (creación, consulta con ownership, cambio de estado)

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/service/AccountService.java`
- Create: `account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java`
- Test: `account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `AccountRepository` (Task 2), `Account`/`AccountStatus` (Task 2), `AccountNotFoundException`/`AccountAccessDeniedException` (Task 4).
- Produces: interfaz `AccountService` con `Account create(String ownerId, Long initialBalance)`, `Account getByIdForRequester(String accountId, String requesterId, boolean isAdmin)`, `Account updateStatus(String accountId, AccountStatus newStatus)`. Consumido por Task 6 (controller).

- [ ] **Step 1: Escribir los tests que fallan**

`account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java`:
```java
package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.exception.AccountAccessDeniedException;
import com.loyalty.account.exception.AccountNotFoundException;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository);
    }

    @Test
    void createPersistsAccountWithOwnerAndActiveStatus() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account created = accountService.create("user-1", 50L);

        assertThat(created.getOwnerId()).isEqualTo("user-1");
        assertThat(created.getBalance()).isEqualTo(50L);
        assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).startsWith("acc-");
    }

    @Test
    void createDefaultsBalanceToZeroWhenNull() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account created = accountService.create("user-1", null);

        assertThat(created.getBalance()).isZero();
    }

    @Test
    void getByIdForRequesterReturnsAccountWhenOwnerMatches() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        Account result = accountService.getByIdForRequester("acc-1", "user-1", false);

        assertThat(result).isEqualTo(account);
    }

    @Test
    void getByIdForRequesterReturnsAccountForAdminRegardlessOfOwner() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        Account result = accountService.getByIdForRequester("acc-1", "another-user", true);

        assertThat(result).isEqualTo(account);
    }

    @Test
    void getByIdForRequesterThrowsAccessDeniedWhenNotOwnerAndNotAdmin() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getByIdForRequester("acc-1", "another-user", false))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    void getByIdForRequesterThrowsNotFoundWhenAccountMissing() {
        when(accountRepository.findById("acc-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getByIdForRequester("acc-missing", "user-1", false))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void updateStatusPersistsNewStatus() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account updated = accountService.updateStatus("acc-1", AccountStatus.INACTIVE);

        assertThat(updated.getStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    private Account accountWith(String id, String ownerId) {
        Account account = new Account();
        account.setId(id);
        account.setOwnerId(ownerId);
        account.setBalance(100L);
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }
}
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: FAIL — `AccountService`/`AccountServiceImpl` no existen.

- [ ] **Step 3: Crear la interfaz**

`account-service/src/main/java/com/loyalty/account/service/AccountService.java`:
```java
package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;

public interface AccountService {

    Account create(String ownerId, Long initialBalance);

    Account getByIdForRequester(String accountId, String requesterId, boolean isAdmin);

    Account updateStatus(String accountId, AccountStatus newStatus);
}
```

- [ ] **Step 4: Implementar el service**

`account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java`:
```java
package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.exception.AccountAccessDeniedException;
import com.loyalty.account.exception.AccountNotFoundException;
import com.loyalty.account.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public Account create(String ownerId, Long initialBalance) {
        Account account = new Account();
        account.setId("acc-" + UUID.randomUUID());
        account.setOwnerId(ownerId);
        account.setBalance(initialBalance == null ? 0L : initialBalance);
        account.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    @Override
    public Account getByIdForRequester(String accountId, String requesterId, boolean isAdmin) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        if (!isAdmin && !account.getOwnerId().equals(requesterId)) {
            throw new AccountAccessDeniedException("No autorizado para acceder a la cuenta " + accountId);
        }

        return account;
    }

    @Override
    public Account updateStatus(String accountId, AccountStatus newStatus) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        account.setStatus(newStatus);
        account.setUpdatedAt(Instant.now());
        return accountRepository.save(account);
    }
}
```

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/service/ account-service/src/test/java/com/loyalty/account/service/
git commit -m "feat: agrega capa de servicio de account con ownership y cambio de estado"
```

---

### Task 6: Seguridad (Resource Server + mapeo de roles Keycloak)

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/security/SecurityConfig.java`
- Test: `account-service/src/test/java/com/loyalty/account/security/SecurityConfigTest.java`

**Interfaces:**
- Consumes: nada nuevo (usa `issuer-uri` de `application.yml`, Task 1).
- Produces: `SecurityFilterChain` que exige autenticación JWT en toda ruta `/accounts/**`, y un `JwtAuthenticationConverter` que mapea el claim `realm_access.roles` de Keycloak a `ROLE_USER`/`ROLE_ADMIN` de Spring Security. Consumido por Task 7 (controller con `@PreAuthorize`).

- [ ] **Step 1: Escribir el test que falla**

`account-service/src/test/java/com/loyalty/account/security/SecurityConfigTest.java`:
```java
package com.loyalty.account.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void mapsRealmRolesToSpringAuthorities() {
        SecurityConfig config = new SecurityConfig();

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .claim("realm_access", Map.of("roles", List.of("USER", "ADMIN")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        JwtAuthenticationToken token = (JwtAuthenticationToken) config.jwtAuthenticationConverter().convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=SecurityConfigTest`
Expected: FAIL — `SecurityConfig` no existe.

- [ ] **Step 3: Crear `SecurityConfig`**

`account-service/src/main/java/com/loyalty/account/security/SecurityConfig.java`:
```java
package com.loyalty.account.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new java.util.ArrayList<>(defaultConverter.convert(jwt));

            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                List<GrantedAuthority> roleAuthorities = roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toList());
                authorities.addAll(roleAuthorities);
            }

            return authorities;
        });
        return converter;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=SecurityConfigTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/security/ account-service/src/test/java/com/loyalty/account/security/
git commit -m "feat: agrega configuracion de seguridad OAuth2 Resource Server con mapeo de roles de Keycloak"
```

---

### Task 7: Controller y endpoints REST

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/controller/AccountController.java`
- Test: `account-service/src/test/java/com/loyalty/account/controller/AccountControllerIT.java`

**Interfaces:**
- Consumes: `AccountService` (Task 5), DTOs (Task 3), `SecurityConfig` (Task 6).
- Produces: `POST /accounts`, `GET /accounts/{id}`, `PATCH /accounts/{id}/status` funcionando end-to-end contra Mongo real (Testcontainers). No produce nada consumido por tareas posteriores (Task 8 es independiente, la saga no pasa por HTTP).

- [ ] **Step 1: Escribir el test de integración que falla**

`account-service/src/test/java/com/loyalty/account/controller/AccountControllerIT.java`:
```java
package com.loyalty.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        accountRepository.deleteAll();
    }

    @Test
    void createAccountReturns201WithOwnerFromJwt() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType("application/json")
                        .content("{\"balance\": 100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("user-1"))
                .andExpect(jsonPath("$.balance").value(100));
    }

    @Test
    void getAccountReturns403WhenNotOwnerAndNotAdmin() throws Exception {
        Account account = new Account();
        account.setId("acc-other");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        mockMvc.perform(get("/accounts/acc-other")
                        .with(jwt().jwt(j -> j.subject("intruder"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateStatusRequiresAdminRole() throws Exception {
        Account account = new Account();
        account.setId("acc-status");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        mockMvc.perform(patch("/accounts/acc-status/status")
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content("{\"status\": \"INACTIVE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/accounts/acc-status/status")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"status\": \"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountControllerIT`
Expected: FAIL — `AccountController` no existe (404 en vez de 201/403/200).

- [ ] **Step 3: Crear el controller**

`account-service/src/main/java/com/loyalty/account/controller/AccountController.java`:
```java
package com.loyalty.account.controller;

import com.loyalty.account.domain.Account;
import com.loyalty.account.dto.AccountResponse;
import com.loyalty.account.dto.CreateAccountRequest;
import com.loyalty.account.dto.UpdateStatusRequest;
import com.loyalty.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                   Authentication authentication) {
        String ownerId = subjectOf(authentication);
        Account created = accountService.create(ownerId, request.getBalance());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getById(@PathVariable("id") String id, Authentication authentication) {
        String requesterId = subjectOf(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        Account account = accountService.getByIdForRequester(id, requesterId, isAdmin);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable("id") String id,
                                                         @Valid @RequestBody UpdateStatusRequest request) {
        Account updated = accountService.updateStatus(id, request.getStatus());
        return ResponseEntity.ok(AccountResponse.from(updated));
    }

    private String subjectOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return jwt.getSubject();
        }
        throw new IllegalStateException("Autenticacion no es JWT");
    }

    private boolean hasRole(Authentication authentication, String role) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().equals(role)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountControllerIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/controller/ account-service/src/test/java/com/loyalty/account/controller/
git commit -m "feat: agrega AccountController con endpoints POST/GET/PATCH y autorizacion"
```

---

### Task 8: Operaciones atómicas de débito/crédito/compensación

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`
- Create: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java:1-6` (extender también la interfaz custom)
- Test: `account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java`

**Interfaces:**
- Consumes: `Account`/`AccountStatus` (Task 2), `MongoTemplate` (autoconfigurado por `spring-boot-starter-data-mongodb`).
- Produces: `AccountAtomicOperations` con `boolean debitIfSufficientBalance(String accountId, long amount)`, `boolean creditIfActive(String accountId, long amount)`, `void creditUnconditionally(String accountId, long amount)` (usado por la compensación). `AccountRepository` pasa a extender también `AccountAtomicOperations` (patrón repositorio Spring Data custom). Consumido por Task 9 (listener de la saga).

- [ ] **Step 1: Escribir el test de integración que falla**

`account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
class AccountAtomicOperationsIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
    }

    @Test
    void debitSucceedsWhenBalanceSufficient() {
        saveAccount("acc-1", 100L, AccountStatus.ACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-1", 40L);

        assertThat(result).isTrue();
        assertThat(accountRepository.findById("acc-1").orElseThrow().getBalance()).isEqualTo(60L);
    }

    @Test
    void debitFailsWhenBalanceInsufficient() {
        saveAccount("acc-2", 10L, AccountStatus.ACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-2", 40L);

        assertThat(result).isFalse();
        assertThat(accountRepository.findById("acc-2").orElseThrow().getBalance()).isEqualTo(10L);
    }

    @Test
    void debitFailsWhenAccountInactive() {
        saveAccount("acc-3", 100L, AccountStatus.INACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-3", 10L);

        assertThat(result).isFalse();
    }

    @Test
    void creditSucceedsWhenAccountActive() {
        saveAccount("acc-4", 50L, AccountStatus.ACTIVE);

        boolean result = accountRepository.creditIfActive("acc-4", 25L);

        assertThat(result).isTrue();
        assertThat(accountRepository.findById("acc-4").orElseThrow().getBalance()).isEqualTo(75L);
    }

    @Test
    void creditFailsWhenAccountInactive() {
        saveAccount("acc-5", 50L, AccountStatus.INACTIVE);

        boolean result = accountRepository.creditIfActive("acc-5", 25L);

        assertThat(result).isFalse();
    }

    @Test
    void creditUnconditionallyRestoresBalanceRegardlessOfStatus() {
        saveAccount("acc-6", 0L, AccountStatus.INACTIVE);

        accountRepository.creditUnconditionally("acc-6", 40L);

        assertThat(accountRepository.findById("acc-6").orElseThrow().getBalance()).isEqualTo(40L);
    }

    @Test
    void concurrentDebitsNeverLeaveBalanceNegative() throws InterruptedException {
        saveAccount("acc-concurrent", 100L, AccountStatus.ACTIVE);

        int attempts = 5;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch latch = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    if (accountRepository.debitIfSufficientBalance("acc-concurrent", 30L)) {
                        successes.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(3);
        assertThat(accountRepository.findById("acc-concurrent").orElseThrow().getBalance()).isEqualTo(10L);
    }

    private void saveAccount(String id, long balance, AccountStatus status) {
        Account account = new Account();
        account.setId(id);
        account.setOwnerId("owner");
        account.setBalance(balance);
        account.setStatus(status);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: FAIL — `AccountRepository` no tiene los métodos `debitIfSufficientBalance`/`creditIfActive`/`creditUnconditionally`.

- [ ] **Step 3: Crear la interfaz de operaciones custom**

`account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`:
```java
package com.loyalty.account.repository;

public interface AccountAtomicOperations {

    boolean debitIfSufficientBalance(String accountId, long amount);

    boolean creditIfActive(String accountId, long amount);

    void creditUnconditionally(String accountId, long amount);
}
```

- [ ] **Step 4: Implementar con `MongoTemplate`**

`account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class AccountAtomicOperationsImpl implements AccountAtomicOperations {

    private final MongoTemplate mongoTemplate;

    public AccountAtomicOperationsImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean debitIfSufficientBalance(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId)
                .and("status").is(AccountStatus.ACTIVE)
                .and("balance").gte(amount));
        Update update = new Update().inc("balance", -amount).set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
    }

    @Override
    public boolean creditIfActive(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId)
                .and("status").is(AccountStatus.ACTIVE));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
    }

    @Override
    public void creditUnconditionally(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        mongoTemplate.updateFirst(query, update, Account.class);
    }
}
```

- [ ] **Step 5: Extender `AccountRepository` con la interfaz custom**

`account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String>, AccountAtomicOperations {
}
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: PASS (7 tests, incluyendo el de concurrencia: exactamente 3 de 5 débitos de 30 sobre saldo 100 tienen éxito, saldo final 10).

- [ ] **Step 7: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/repository/ account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java
git commit -m "feat: agrega operaciones atomicas de debito/credito/compensacion con MongoTemplate"
```

---

### Task 9: Eventos de la saga (listener Kafka + publisher + idempotencia)

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/DebitRequestedEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/CreditRequestedEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/CompensateDebitEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/DebitResultEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/CreditResultEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/ProcessedEvent.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/ProcessedEventRepository.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/SagaEventPublisher.java`
- Create: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`
- Test: `account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java`

**Interfaces:**
- Consumes: `AccountRepository` (débito/crédito atómico, Task 8), `AccountNotFoundException` no aplica aquí (la saga no lanza HTTP).
- Produces: `SagaEventListener` con métodos `@KafkaListener` para topics `debit-events`, `credit-events`, `transfer-compensation`; publica resultados vía `SagaEventPublisher.publishDebitResult(...)`/`publishCreditResult(...)`. Este es el último componente de Fase 1 — no hay tareas posteriores en este plan que lo consuman (lo consumirá `transfer-service` en Fase 2).

- [ ] **Step 1: Escribir el test de integración que falla**

`account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java`:
```java
package com.loyalty.account.saga;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import com.loyalty.account.saga.events.CreditRequestedEvent;
import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitRequestedEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"debit-events", "credit-events", "transfer-compensation"})
class SagaEventListenerIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestResultCollector resultCollector;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        resultCollector.clear();
    }

    @Test
    void debitRequestedPublishesSucceededWhenBalanceSufficient() throws InterruptedException {
        saveAccount("acc-1", 100L);

        kafkaTemplate.send("debit-events", "tx-1",
                new DebitRequestedEvent("tx-1", "acc-1", 40L, Instant.now().toString()));

        DebitResultEvent result = resultCollector.pollDebitResult(Duration.ofSeconds(10));

        assertThat(result.transactionId()).isEqualTo("tx-1");
        assertThat(result.success()).isTrue();
        assertThat(accountRepository.findById("acc-1").orElseThrow().getBalance()).isEqualTo(60L);
    }

    @Test
    void debitRequestedPublishesFailedWhenBalanceInsufficient() throws InterruptedException {
        saveAccount("acc-2", 10L);

        kafkaTemplate.send("debit-events", "tx-2",
                new DebitRequestedEvent("tx-2", "acc-2", 40L, Instant.now().toString()));

        DebitResultEvent result = resultCollector.pollDebitResult(Duration.ofSeconds(10));

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void creditRequestedPublishesSucceededWhenAccountActive() throws InterruptedException {
        saveAccount("acc-3", 10L);

        kafkaTemplate.send("credit-events", "tx-3",
                new CreditRequestedEvent("tx-3", "acc-3", 25L, Instant.now().toString()));

        CreditResultEvent result = resultCollector.pollCreditResult(Duration.ofSeconds(10));

        assertThat(result.success()).isTrue();
        assertThat(accountRepository.findById("acc-3").orElseThrow().getBalance()).isEqualTo(35L);
    }

    @Test
    void duplicateDebitEventIsIgnoredThanksToIdempotency() throws InterruptedException {
        saveAccount("acc-4", 100L);

        DebitRequestedEvent event = new DebitRequestedEvent("tx-4", "acc-4", 30L, Instant.now().toString());
        kafkaTemplate.send("debit-events", "tx-4", event);
        resultCollector.pollDebitResult(Duration.ofSeconds(10));

        kafkaTemplate.send("debit-events", "tx-4", event);
        resultCollector.pollDebitResult(Duration.ofSeconds(10));

        assertThat(accountRepository.findById("acc-4").orElseThrow().getBalance()).isEqualTo(70L);
    }

    private void saveAccount(String id, long balance) {
        Account account = new Account();
        account.setId(id);
        account.setOwnerId("owner");
        account.setBalance(balance);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
    }
}
```

*(`TestResultCollector` es un `@TestComponent` auxiliar que se crea en el Step 3 junto con el resto, escuchando los topics de resultado para que el test pueda hacer polling sobre lo publicado — necesario porque `SagaEventPublisher` publica a Kafka, no retorna el evento directamente.)*

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=SagaEventListenerIT`
Expected: FAIL — ninguna de las clases del paquete `saga` existe.

- [ ] **Step 3: Crear los eventos (records)**

`account-service/src/main/java/com/loyalty/account/saga/events/DebitRequestedEvent.java`:
```java
package com.loyalty.account.saga.events;

public record DebitRequestedEvent(String transactionId, String sourceAccountId, long amount, String timestamp) {
}
```

`account-service/src/main/java/com/loyalty/account/saga/events/CreditRequestedEvent.java`:
```java
package com.loyalty.account.saga.events;

public record CreditRequestedEvent(String transactionId, String targetAccountId, long amount, String timestamp) {
}
```

`account-service/src/main/java/com/loyalty/account/saga/events/CompensateDebitEvent.java`:
```java
package com.loyalty.account.saga.events;

public record CompensateDebitEvent(String transactionId, String sourceAccountId, long amount) {
}
```

`account-service/src/main/java/com/loyalty/account/saga/events/DebitResultEvent.java`:
```java
package com.loyalty.account.saga.events;

public record DebitResultEvent(String transactionId, boolean success, String reason) {

    public static DebitResultEvent succeeded(String transactionId) {
        return new DebitResultEvent(transactionId, true, null);
    }

    public static DebitResultEvent failed(String transactionId, String reason) {
        return new DebitResultEvent(transactionId, false, reason);
    }
}
```

`account-service/src/main/java/com/loyalty/account/saga/events/CreditResultEvent.java`:
```java
package com.loyalty.account.saga.events;

public record CreditResultEvent(String transactionId, boolean success, String reason) {

    public static CreditResultEvent succeeded(String transactionId) {
        return new CreditResultEvent(transactionId, true, null);
    }

    public static CreditResultEvent failed(String transactionId, String reason) {
        return new CreditResultEvent(transactionId, false, reason);
    }
}
```

- [ ] **Step 4: Crear el registro de eventos procesados (idempotencia)**

`account-service/src/main/java/com/loyalty/account/saga/ProcessedEvent.java`:
```java
package com.loyalty.account.saga;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "processed_events")
public class ProcessedEvent {

    @Id
    private String id;

    public ProcessedEvent() {
    }

    public ProcessedEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
```

`account-service/src/main/java/com/loyalty/account/saga/ProcessedEventRepository.java`:
```java
package com.loyalty.account.saga;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEvent, String> {
}
```

*(La clave `id` de `ProcessedEvent` se construye como `"<transactionId>:<tipoDeEvento>"`, ej. `"tx-4:DEBIT"` — así un mismo `transactionId` puede procesarse una vez para débito y otra para crédito sin colisionar. `save()` sobre un `_id` de Mongo ya existente lanza `DuplicateKeyException`; el listener la captura para reconocer "ya procesado" e ignorar el evento duplicado.)*

- [ ] **Step 5: Crear el publisher**

`account-service/src/main/java/com/loyalty/account/saga/SagaEventPublisher.java`:
```java
package com.loyalty.account.saga;

import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SagaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SagaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDebitResult(DebitResultEvent event) {
        kafkaTemplate.send("debit-events", event.transactionId(), event);
    }

    public void publishCreditResult(CreditResultEvent event) {
        kafkaTemplate.send("credit-events", event.transactionId(), event);
    }
}
```

- [ ] **Step 6: Crear el listener**

`account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`:
```java
package com.loyalty.account.saga;

import com.loyalty.account.repository.AccountRepository;
import com.loyalty.account.saga.events.CompensateDebitEvent;
import com.loyalty.account.saga.events.CreditRequestedEvent;
import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitRequestedEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SagaEventListener {

    private final AccountRepository accountRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaEventPublisher publisher;

    public SagaEventListener(AccountRepository accountRepository,
                              ProcessedEventRepository processedEventRepository,
                              SagaEventPublisher publisher) {
        this.accountRepository = accountRepository;
        this.processedEventRepository = processedEventRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "debit-events", groupId = "account-service-debit")
    public void onDebitRequested(DebitRequestedEvent event) {
        if (event.sourceAccountId() == null) {
            return;
        }
        String idempotencyKey = event.transactionId() + ":DEBIT";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }

        boolean debited = accountRepository.debitIfSufficientBalance(event.sourceAccountId(), event.amount());
        if (debited) {
            publisher.publishDebitResult(DebitResultEvent.succeeded(event.transactionId()));
        } else {
            publisher.publishDebitResult(DebitResultEvent.failed(event.transactionId(), "INSUFFICIENT_BALANCE"));
        }
    }

    @KafkaListener(topics = "credit-events", groupId = "account-service-credit")
    public void onCreditRequested(CreditRequestedEvent event) {
        if (event.targetAccountId() == null) {
            return;
        }
        String idempotencyKey = event.transactionId() + ":CREDIT";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }

        boolean credited = accountRepository.creditIfActive(event.targetAccountId(), event.amount());
        if (credited) {
            publisher.publishCreditResult(CreditResultEvent.succeeded(event.transactionId()));
        } else {
            publisher.publishCreditResult(CreditResultEvent.failed(event.transactionId(), "TARGET_INACTIVE"));
        }
    }

    @KafkaListener(topics = "transfer-compensation", groupId = "account-service-compensation")
    public void onCompensateDebit(CompensateDebitEvent event) {
        String idempotencyKey = event.transactionId() + ":COMPENSATION";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }
        accountRepository.creditUnconditionally(event.sourceAccountId(), event.amount());
    }

    private boolean tryMarkProcessed(String idempotencyKey) {
        try {
            processedEventRepository.save(new ProcessedEvent(idempotencyKey));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
```

- [ ] **Step 7: Crear el `TestResultCollector` auxiliar de test**

`account-service/src/test/java/com/loyalty/account/saga/TestResultCollector.java`:
```java
package com.loyalty.account.saga;

import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@TestComponent
public class TestResultCollector {

    private final LinkedBlockingQueue<DebitResultEvent> debitResults = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<CreditResultEvent> creditResults = new LinkedBlockingQueue<>();

    @KafkaListener(topics = "debit-events", groupId = "test-collector-debit")
    public void onDebitResult(DebitResultEvent event) {
        debitResults.add(event);
    }

    @KafkaListener(topics = "credit-events", groupId = "test-collector-credit")
    public void onCreditResult(CreditResultEvent event) {
        creditResults.add(event);
    }

    public DebitResultEvent pollDebitResult(Duration timeout) throws InterruptedException {
        return debitResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CreditResultEvent pollCreditResult(Duration timeout) throws InterruptedException {
        return creditResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void clear() {
        debitResults.clear();
        creditResults.clear();
    }
}
```

*(Nota: `TestResultCollector` también escucha `debit-events`/`credit-events`, el mismo topic donde `SagaEventListener` escucha las solicitudes — en este plan, resultado y solicitud comparten topic por simplicidad, tal como permite la spec ("o uno de resultado dedicado — decisión de implementación, ambos válidos"). El listener de solicitudes (`onDebitRequested`) filtra por tipo de evento gracias a la deserialización JSON tipada; el `TestResultCollector` debe registrarse en un `@TestConfiguration` separado para no interferir con el `SagaEventListener` de producción — si en la ejecución real esto genera colisión de deserialización entre `DebitRequestedEvent` y `DebitResultEvent` en el mismo topic, la corrección de implementación es separar en topics `debit-events` y `debit-results` distintos; documentar esa decisión en el plan de Fase 2 si aplica.)*

- [ ] **Step 8: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=SagaEventListenerIT`
Expected: PASS (4 tests). Si falla por colisión de tipos en el mismo topic (ver nota del Step 7), separar `debit-events`/`credit-events` (solicitudes) de `debit-results`/`credit-results` (resultados) — ajustar `SagaEventPublisher`, `SagaEventListener` y `TestResultCollector` a los nuevos nombres de topic, y agregar la creación de esos 2 topics nuevos al script `docker/kafka/create-topics.sh` de Fase 0 en un commit separado.

- [ ] **Step 9: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/saga/ account-service/src/test/java/com/loyalty/account/saga/
git commit -m "feat: agrega listener y publisher de eventos de la saga con idempotencia"
```

---

## Self-Review

**1. Spec coverage:**
- Los 4 endpoints de `/accounts` con su matriz de autorización → Task 6 (seguridad), Task 7 (controller).
- Modelo de datos `Account` → Task 2.
- Capas Controller/Service/Repository → Tasks 2, 5, 7.
- Reglas de negocio (creación, ownership, cambio de estado, débito/crédito atómico, compensación, idempotencia) → Tasks 5, 8, 9.
- Seguridad (Resource Server, mapeo de roles, ownership en service layer) → Task 6, Task 5/7.
- Manejo de errores (`GlobalExceptionHandler`, excepciones de dominio) → Task 4.
- Testing (unit de creación/ownership/débito insuficiente, integration de los 4 endpoints con 401/403, test de concurrencia, test de idempotencia) → Tasks 5, 7, 8, 9.
- Criterios de aceptación de la spec: matriz de autorización (Task 7), concurrencia sin saldo negativo (Task 8, `concurrentDebitsNeverLeaveBalanceNegative`), registro en Eureka (Task 1), eventos de resultado publicados (Task 9).
- `GET /accounts/{id}/transactions` (delegación REST a `transfer-service`) — **gap identificado**: la spec deja esta decisión para el plan, pero `transfer-service` (dueño de `Transaction`) no existe todavía (es Fase 2). Este endpoint queda **fuera de este plan** y se implementará en la Fase 2 o en un ajuste posterior a Fase 1, una vez `transfer-service` exponga el endpoint que `account-service` necesita consumir. Se documenta aquí para que no se pierda de vista.

**2. Placeholder scan:** sin TBD/TODO; el único punto abierto (Step 7-8 de Task 9, colisión de tipos en el mismo topic) tiene una salida concreta y accionable, no un placeholder — es una decisión de diseño que se resuelve en tiempo de ejecución con una alternativa ya especificada.

**3. Type consistency:** `Account`/`AccountStatus` (Task 2) se usan idénticos en Tasks 3, 5, 7, 8. `AccountRepository` pasa de `MongoRepository<Account, String>` (Task 2) a `MongoRepository<Account, String>, AccountAtomicOperations` (Task 8) — el cambio se declara explícitamente como "Modify" en el header de Task 8. Los eventos (`DebitRequestedEvent`, etc.) se definen una sola vez en Task 9 y se usan consistentemente en listener, publisher y test.

## Gap explícito para la siguiente fase

`GET /accounts/{id}/transactions` no se implementa en este plan — depende de `transfer-service` (Fase 2). Debe agregarse como tarea explícita en el plan de Fase 2, o como un ajuste a Fase 1 después de que `transfer-service` exista.

## Correcciones descubiertas durante la ejecución

El plan pasó el self-review documental y las 9 tareas pasaron individualmente, pero `mvn clean install` sobre el módulo completo reveló un bug de interacción entre tareas que ninguna tarea aislada podía detectar:

- **`AccountServiceApplicationTests` (Task 1) rompía al agregar los `@KafkaListener` de Task 9.** El test básico de contexto (`@SpringBootTest` sin Testcontainers) intentaba arrancar los listeners Kafka reales al levantar el `ApplicationContext`, y estos fallaban con `ConfigException: No resolvable bootstrap urls given in bootstrap.servers` porque el hostname `kafka` (definido en `application.yml` para el entorno Docker) no es resoluble en la máquina del desarrollador fuera de la red de Compose. Fix: `@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")` en `AccountServiceApplicationTests`, para que ese test de contexto no arranque consumidores reales — su propósito es solo verificar que el `ApplicationContext` carga, no probar Kafka (eso ya lo cubre `SagaEventListenerIT` con Kafka embebido).

Verificado con `mvn clean install` completo del monorepo (los 3 módulos con código: `eureka-server`, `account-service`, más los 4 placeholders) — `BUILD SUCCESS`, exit code 0.
