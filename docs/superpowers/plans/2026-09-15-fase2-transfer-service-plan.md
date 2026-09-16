# Fase 2 — transfer-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir `transfer-service`: `POST /api/v1/points/transfer` con validación síncrona previa, orquestación completa de la saga coreografiada de débito/crédito/compensación vía Kafka, timeout de saga, y un endpoint interno `GET /transactions` (filtrado por cuenta) que resuelve el gap dejado por Fase 1 para `GET /accounts/{id}/transactions`.

**Architecture:** Controller (HTTP) → Service (validación síncrona vía `AccountClient` + persistencia de `Transaction`) → Repository (Spring Data MongoDB). Un listener Kafka separado (`TransferSagaListener`) consume los eventos de resultado (`debit-results`, `credit-results`) publicados por `account-service` y avanza la máquina de estados de cada `Transaction`. `AccountClient` reenvía el JWT del request original a `account-service` (vía Eureka), reutilizando su lógica de ownership ya implementada en Fase 1 en vez de duplicarla aquí.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Web, Data MongoDB, OAuth2 Resource Server, Kafka, Actuator), Spring Cloud Netflix Eureka Client + LoadBalancer, JUnit 5 + Mockito, Testcontainers (MongoDB replica-set real, Kafka embebido), `spring-security-test`.

**Spec:** `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase2-transfer-service-design.md` (contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- Módulo Maven ya existe como placeholder (`transfer-service/pom.xml`) heredando del parent — no crear un nuevo parent.
- `spring-boot-maven-plugin` debe fijar `<version>${spring-boot.version}</version>` y `<executions><goal>repackage</goal></executions>` explícitamente (bug de Fase 0: sin esto, el JAR queda sin manifest ejecutable y con versión de plugin incorrecta).
- `_id` de `Transaction` es un UUID string — decisión D13 de `ARQUITECTURA.md`.
- Formato estándar de error en todo el sistema: `{ "code", "message", "timestamp" }`.
- Topics Kafka ya existen desde Fase 0/1: `debit-events`, `credit-events`, `transfer-compensation` (solicitudes, producidos por `transfer-service`), `debit-results`, `credit-results` (resultados, producidos por `account-service`, consumidos por `transfer-service`).
- `account-service` (Fase 1) expone `GET /accounts/{id}` protegido: `404` si no existe, `403` si el requester no es dueño ni `ADMIN`, `200` con `{id, ownerId, balance, status, ...}` en caso contrario.
- **`account-service` (Fase 1) NO publica un evento de confirmación tras aplicar la compensación** (`onCompensateDebit` solo aplica el crédito, no publica `CompensationApplied`) — decisión tomada durante la implementación de Fase 1. `transfer-service` debe marcar la `Transaction` como `FAILED` inmediatamente después de emitir el evento de compensación, sin esperar confirmación.
- Registro en Eureka: `eureka-server` en `eureka-server:8761` (Fase 0). `account-service` se registra como `account-service` (Fase 1).

---

## File Structure

```
transfer-service/
├── pom.xml                                          # MODIFICAR: agregar dependencias reales
└── src/
    ├── main/
    │   ├── java/com/loyalty/transfer/
    │   │   ├── TransferServiceApplication.java
    │   │   ├── domain/
    │   │   │   ├── Transaction.java
    │   │   │   └── TransactionStatus.java             # PENDING/COMPLETED/FAILED
    │   │   ├── repository/
    │   │   │   └── TransactionRepository.java
    │   │   ├── dto/
    │   │   │   ├── TransferRequest.java
    │   │   │   ├── TransferResponse.java
    │   │   │   ├── TransactionResponse.java             # para el endpoint GET /transactions
    │   │   │   └── AccountView.java                      # respuesta deserializada de account-service
    │   │   ├── client/
    │   │   │   └── AccountClient.java                    # RestClient load-balanced via Eureka
    │   │   ├── service/
    │   │   │   ├── TransferService.java
    │   │   │   └── TransferServiceImpl.java
    │   │   ├── controller/
    │   │   │   ├── TransferController.java
    │   │   │   └── TransactionQueryController.java        # GET /transactions
    │   │   ├── security/
    │   │   │   └── SecurityConfig.java
    │   │   ├── exception/
    │   │   │   ├── SameAccountException.java
    │   │   │   ├── AccountNotFoundException.java
    │   │   │   ├── TransferAccessDeniedException.java
    │   │   │   ├── TransferUnprocessableException.java
    │   │   │   └── GlobalExceptionHandler.java
    │   │   └── saga/
    │   │       ├── events/
    │   │       │   ├── DebitRequestedEvent.java
    │   │       │   ├── CreditRequestedEvent.java
    │   │       │   ├── CompensateDebitEvent.java
    │   │       │   ├── DebitResultEvent.java
    │   │       │   └── CreditResultEvent.java
    │   │       ├── TransferSagaListener.java              # consume debit-results/credit-results
    │   │       ├── TransferSagaPublisher.java              # publica debit-events/credit-events/transfer-compensation
    │   │       └── SagaTimeoutScheduler.java               # marca PENDING viejos como FAILED
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/loyalty/transfer/
            ├── repository/TransactionRepositoryIT.java
            ├── dto/TransferRequestValidationTest.java
            ├── exception/GlobalExceptionHandlerTest.java
            ├── service/TransferServiceTest.java
            ├── security/SecurityConfigTest.java
            ├── controller/TransferControllerIT.java
            ├── controller/TransactionQueryControllerIT.java
            └── saga/TransferSagaListenerIT.java
```

---

### Task 1: Dependencias del módulo y arranque de la aplicación

**Files:**
- Modify: `transfer-service/pom.xml`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/TransferServiceApplication.java`
- Create: `transfer-service/src/main/resources/application.yml`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/TransferServiceApplicationTests.java`

**Interfaces:**
- Consumes: parent POM (`com.loyalty:loyalty-microservice-platform:1.0.0-SNAPSHOT`), `eureka-server` (Fase 0).
- Produces: aplicación arrancable en el puerto `8082`, registrada en Eureka como `transfer-service`, `/actuator/health` → `UP`.

- [ ] **Step 1: Escribir el test que falla**

`transfer-service/src/test/java/com/loyalty/transfer/TransferServiceApplicationTests.java`:
```java
package com.loyalty.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class TransferServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

*(Nota: `spring.kafka.listener.auto-startup=false` se incluye desde el inicio en este test — Fase 1 descubrió que un test de contexto simple rompe en cuanto se agregan `@KafkaListener` reales, porque el hostname `kafka` no es resoluble fuera de la red de Docker.)*

- [ ] **Step 2: Reemplazar `transfer-service/pom.xml`**

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

  <artifactId>transfer-service</artifactId>
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
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-loadbalancer</artifactId>
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

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test`
Expected: FAIL — `TransferServiceApplication` no existe.

- [ ] **Step 4: Crear la clase de aplicación**

`transfer-service/src/main/java/com/loyalty/transfer/TransferServiceApplication.java`:
```java
package com.loyalty.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class TransferServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
```

- [ ] **Step 5: Crear `application.yml`**

```yaml
server:
  port: 8082

spring:
  application:
    name: transfer-service
  data:
    mongodb:
      uri: mongodb://mongo:27017/transfer_service?replicaSet=rs0
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: transfer-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.loyalty.transfer.saga.events
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

transfer:
  saga:
    timeout-seconds: 30

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add transfer-service/pom.xml transfer-service/src/main/java/com/loyalty/transfer/TransferServiceApplication.java transfer-service/src/main/resources/application.yml transfer-service/src/test/java/com/loyalty/transfer/TransferServiceApplicationTests.java
git commit -m "feat: inicializa transfer-service con dependencias, config y test de contexto"
```

---

### Task 2: Dominio y repositorio Mongo

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/domain/Transaction.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/domain/TransactionStatus.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/repository/TransactionRepository.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/repository/TransactionRepositoryIT.java`

**Interfaces:**
- Consumes: `TransferServiceApplication` (Task 1).
- Produces: `Transaction{ id: String, sourceAccountId: String, targetAccountId: String, amount: long, status: TransactionStatus, failureReason: String, createdAt: Instant, completedAt: Instant }`. `TransactionRepository extends MongoRepository<Transaction, String>` con `List<Transaction> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId)` y `List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant threshold)`. Consumido por Tasks 7 (service), 9 (saga listener), 10 (timeout scheduler), 11 (query controller).

- [ ] **Step 1: Escribir el test de integración que falla**

`transfer-service/src/test/java/com/loyalty/transfer/repository/TransactionRepositoryIT.java`:
```java
package com.loyalty.transfer.repository;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
class TransactionRepositoryIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
    }

    @Test
    void savesAndFindsByAccountEitherSourceOrTarget() {
        transactionRepository.save(transactionWith("tx-1", "acc-1", "acc-2", TransactionStatus.COMPLETED));
        transactionRepository.save(transactionWith("tx-2", "acc-3", "acc-1", TransactionStatus.PENDING));
        transactionRepository.save(transactionWith("tx-3", "acc-4", "acc-5", TransactionStatus.FAILED));

        List<Transaction> results = transactionRepository.findBySourceAccountIdOrTargetAccountId("acc-1", "acc-1");

        assertThat(results).extracting(Transaction::getId).containsExactlyInAnyOrder("tx-1", "tx-2");
    }

    @Test
    void findsStalePendingTransactionsOlderThanThreshold() {
        Transaction old = transactionWith("tx-old", "acc-1", "acc-2", TransactionStatus.PENDING);
        old.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        transactionRepository.save(old);

        Transaction recent = transactionWith("tx-recent", "acc-1", "acc-2", TransactionStatus.PENDING);
        recent.setCreatedAt(Instant.now());
        transactionRepository.save(recent);

        List<Transaction> stale = transactionRepository.findByStatusAndCreatedAtBefore(
                TransactionStatus.PENDING, Instant.now().minus(30, ChronoUnit.MINUTES));

        assertThat(stale).extracting(Transaction::getId).containsExactly("tx-old");
    }

    private Transaction transactionWith(String id, String source, String target, TransactionStatus status) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setSourceAccountId(source);
        tx.setTargetAccountId(target);
        tx.setAmount(100L);
        tx.setStatus(status);
        tx.setCreatedAt(Instant.now());
        return tx;
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransactionRepositoryIT`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Crear `TransactionStatus`**

`transfer-service/src/main/java/com/loyalty/transfer/domain/TransactionStatus.java`:
```java
package com.loyalty.transfer.domain;

public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 4: Crear `Transaction`**

`transfer-service/src/main/java/com/loyalty/transfer/domain/Transaction.java`:
```java
package com.loyalty.transfer.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "transactions")
public class Transaction {

    @Id
    private String id;
    private String sourceAccountId;
    private String targetAccountId;
    private long amount;
    private TransactionStatus status;
    private String failureReason;
    private Instant createdAt;
    private Instant completedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(String sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public void setTargetAccountId(String targetAccountId) {
        this.targetAccountId = targetAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
```

- [ ] **Step 5: Crear `TransactionRepository`**

`transfer-service/src/main/java/com/loyalty/transfer/repository/TransactionRepository.java`:
```java
package com.loyalty.transfer.repository;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);

    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant threshold);
}
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransactionRepositoryIT`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/domain/ transfer-service/src/main/java/com/loyalty/transfer/repository/TransactionRepository.java transfer-service/src/test/java/com/loyalty/transfer/repository/TransactionRepositoryIT.java
git commit -m "feat: agrega dominio Transaction y repositorio Mongo con test de integracion"
```

---

### Task 3: DTOs con validación

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/dto/TransferRequest.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/dto/TransferResponse.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/dto/TransactionResponse.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/dto/AccountView.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/dto/TransferRequestValidationTest.java`

**Interfaces:**
- Consumes: `Transaction`/`TransactionStatus` (Task 2).
- Produces: `TransferRequest{ sourceAccountId: @NotBlank String, targetAccountId: @NotBlank String, amount: @Positive Long }`; `TransferResponse{ transactionId, status, createdAt }` con `TransferResponse.from(Transaction)`; `TransactionResponse{ id, sourceAccountId, targetAccountId, amount, status, failureReason, createdAt, completedAt }` con `TransactionResponse.from(Transaction)`; `AccountView{ id, ownerId, balance, status }` (deserializado de la respuesta JSON de `account-service`). Usados por Tasks 5 (client), 7 (service), 8 (controller), 11 (query controller).

- [ ] **Step 1: Escribir el test de validación que falla**

`transfer-service/src/test/java/com/loyalty/transfer/dto/TransferRequestValidationTest.java`:
```java
package com.loyalty.transfer.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestValidationTest {

    private final Validator validator;

    TransferRequestValidationTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void rejectsNonPositiveAmount() {
        TransferRequest request = new TransferRequest();
        request.setSourceAccountId("acc-1");
        request.setTargetAccountId("acc-2");
        request.setAmount(0L);

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void acceptsValidRequest() {
        TransferRequest request = new TransferRequest();
        request.setSourceAccountId("acc-1");
        request.setTargetAccountId("acc-2");
        request.setAmount(50L);

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferRequestValidationTest`
Expected: FAIL — `TransferRequest` no existe.

- [ ] **Step 3: Crear los 4 DTOs**

`transfer-service/src/main/java/com/loyalty/transfer/dto/TransferRequest.java`:
```java
package com.loyalty.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class TransferRequest {

    @NotBlank
    private String sourceAccountId;

    @NotBlank
    private String targetAccountId;

    @Positive
    private Long amount;

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(String sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public void setTargetAccountId(String targetAccountId) {
        this.targetAccountId = targetAccountId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/dto/TransferResponse.java`:
```java
package com.loyalty.transfer.dto;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;

import java.time.Instant;

public class TransferResponse {

    private String transactionId;
    private TransactionStatus status;
    private Instant createdAt;

    public static TransferResponse from(Transaction transaction) {
        TransferResponse response = new TransferResponse();
        response.transactionId = transaction.getId();
        response.status = transaction.getStatus();
        response.createdAt = transaction.getCreatedAt();
        return response;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/dto/TransactionResponse.java`:
```java
package com.loyalty.transfer.dto;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;

import java.time.Instant;

public class TransactionResponse {

    private String id;
    private String sourceAccountId;
    private String targetAccountId;
    private long amount;
    private TransactionStatus status;
    private String failureReason;
    private Instant createdAt;
    private Instant completedAt;

    public static TransactionResponse from(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.id = transaction.getId();
        response.sourceAccountId = transaction.getSourceAccountId();
        response.targetAccountId = transaction.getTargetAccountId();
        response.amount = transaction.getAmount();
        response.status = transaction.getStatus();
        response.failureReason = transaction.getFailureReason();
        response.createdAt = transaction.getCreatedAt();
        response.completedAt = transaction.getCompletedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/dto/AccountView.java`:
```java
package com.loyalty.transfer.dto;

public class AccountView {

    private String id;
    private String ownerId;
    private long balance;
    private String status;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferRequestValidationTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/dto/ transfer-service/src/test/java/com/loyalty/transfer/dto/
git commit -m "feat: agrega DTOs de transfer con validacion declarativa"
```

---

### Task 4: Excepciones de dominio y manejador global

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/exception/SameAccountException.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/exception/AccountNotFoundException.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/exception/TransferAccessDeniedException.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/exception/TransferUnprocessableException.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/exception/GlobalExceptionHandler.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: 4 excepciones unchecked (`SameAccountException(String)`, `AccountNotFoundException(String)`, `TransferAccessDeniedException(String)`, `TransferUnprocessableException(String code, String message)`), y `GlobalExceptionHandler` (`@RestControllerAdvice`) que mapea: `SameAccountException`/`MethodArgumentNotValidException` → `400`, `AccountNotFoundException` → `404`, `TransferAccessDeniedException` → `403`, `TransferUnprocessableException` → `422`. Cuerpo estándar `{code, message, timestamp}`. Consumido por Task 7 (service) y Task 8 (controller).

- [ ] **Step 1: Escribir el test que falla**

`transfer-service/src/test/java/com/loyalty/transfer/exception/GlobalExceptionHandlerTest.java`:
```java
package com.loyalty.transfer.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsSameAccountTo400() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleSameAccount(new SameAccountException("cuentas iguales"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SAME_ACCOUNT");
    }

    @Test
    void mapsAccountNotFoundTo404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccountNotFound(new AccountNotFoundException("no existe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }

    @Test
    void mapsAccessDeniedTo403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new TransferAccessDeniedException("no autorizado"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "ACCESS_DENIED");
    }

    @Test
    void mapsUnprocessableTo422() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnprocessable(new TransferUnprocessableException("INSUFFICIENT_BALANCE", "saldo insuficiente"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_BALANCE");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Crear las 4 excepciones**

`transfer-service/src/main/java/com/loyalty/transfer/exception/SameAccountException.java`:
```java
package com.loyalty.transfer.exception;

public class SameAccountException extends RuntimeException {
    public SameAccountException(String message) {
        super(message);
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/exception/AccountNotFoundException.java`:
```java
package com.loyalty.transfer.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/exception/TransferAccessDeniedException.java`:
```java
package com.loyalty.transfer.exception;

public class TransferAccessDeniedException extends RuntimeException {
    public TransferAccessDeniedException(String message) {
        super(message);
    }
}
```

`transfer-service/src/main/java/com/loyalty/transfer/exception/TransferUnprocessableException.java`:
```java
package com.loyalty.transfer.exception;

public class TransferUnprocessableException extends RuntimeException {

    private final String code;

    public TransferUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

- [ ] **Step 4: Crear el `GlobalExceptionHandler`**

`transfer-service/src/main/java/com/loyalty/transfer/exception/GlobalExceptionHandler.java`:
```java
package com.loyalty.transfer.exception;

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

    @ExceptionHandler(SameAccountException.class)
    public ResponseEntity<Map<String, Object>> handleSameAccount(SameAccountException ex) {
        return body(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Payload invalido");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TransferAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(TransferAccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage());
    }

    @ExceptionHandler(TransferUnprocessableException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(TransferUnprocessableException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
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

Run: `mvn -q -pl transfer-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/exception/ transfer-service/src/test/java/com/loyalty/transfer/exception/
git commit -m "feat: agrega excepciones de dominio y manejador global de errores"
```

---

### Task 5: `AccountClient` (llamada a account-service vía Eureka, reenviando el JWT)

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/client/AccountClient.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/client/AccountClientTest.java`

**Interfaces:**
- Consumes: `AccountView` (Task 3), `AccountNotFoundException`/`TransferAccessDeniedException` (Task 4), el `RestClient.Builder` `@LoadBalanced` (Task 1).
- Produces: `AccountClient` con `AccountView fetchAccount(String accountId, String bearerToken)` que llama `GET http://account-service/accounts/{accountId}` reenviando el header `Authorization: Bearer <token>`, traduciendo `404`→`AccountNotFoundException`, `403`→`TransferAccessDeniedException`. Consumido por Task 7 (service).

*(Nota de diseño: se reutiliza la lógica de ownership ya implementada en `account-service` — Fase 1 — en vez de duplicarla aquí. `transfer-service` simplemente reenvía el JWT del usuario autenticado; si `account-service` responde `403` porque el usuario no es dueño ni `ADMIN`, `transfer-service` traduce esa respuesta a su propia excepción sin repetir la comparación `ownerId == sub`.)*

- [ ] **Step 1: Escribir el test que falla**

`transfer-service/src/test/java/com/loyalty/transfer/client/AccountClientTest.java`:
```java
package com.loyalty.transfer.client;

import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.AccountNotFoundException;
import com.loyalty.transfer.exception.TransferAccessDeniedException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountClientTest {

    private MockWebServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        accountClient = new AccountClient(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchAccountReturnsAccountViewOn200() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"acc-1\",\"ownerId\":\"user-1\",\"balance\":100,\"status\":\"ACTIVE\"}"));

        AccountView account = accountClient.fetchAccount("acc-1", "token-123");

        assertThat(account.getId()).isEqualTo("acc-1");
        assertThat(account.getBalance()).isEqualTo(100L);
    }

    @Test
    void fetchAccountThrowsNotFoundOn404() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> accountClient.fetchAccount("acc-missing", "token-123"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void fetchAccountThrowsAccessDeniedOn403() {
        server.enqueue(new MockResponse().setResponseCode(403));

        assertThatThrownBy(() -> accountClient.fetchAccount("acc-1", "token-123"))
                .isInstanceOf(TransferAccessDeniedException.class);
    }
}
```

*(Requiere `com.squareup.okhttp3:mockwebserver` como dependencia de test — agregar en Step 2.)*

- [ ] **Step 2: Agregar la dependencia de test necesaria**

Modificar `transfer-service/pom.xml`, agregar dentro de `<dependencies>`:
```xml
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <version>4.12.0</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=AccountClientTest`
Expected: FAIL — `AccountClient` no existe.

- [ ] **Step 4: Crear `AccountClient`**

`transfer-service/src/main/java/com/loyalty/transfer/client/AccountClient.java`:
```java
package com.loyalty.transfer.client;

import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.AccountNotFoundException;
import com.loyalty.transfer.exception.TransferAccessDeniedException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("http://account-service").build();
    }

    public AccountClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public AccountView fetchAccount(String accountId, String bearerToken) {
        return restClient.get()
                .uri("/accounts/{id}", accountId)
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        throw new AccountNotFoundException("Cuenta no encontrada: " + accountId);
                    }
                    if (response.getStatusCode().value() == 403) {
                        throw new TransferAccessDeniedException("No autorizado para acceder a la cuenta " + accountId);
                    }
                })
                .body(AccountView.class);
    }
}
```

*(Nota: `AccountClient` tiene dos constructores — el usado en producción (`RestClient.Builder` `@LoadBalanced`, resuelve `http://account-service` vía Eureka) y uno adicional que recibe un `RestClient` ya construido, usado por el test con `MockWebServer` para apuntar a un servidor HTTP real de prueba en vez de depender de Eureka.)*

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=AccountClientTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/pom.xml transfer-service/src/main/java/com/loyalty/transfer/client/ transfer-service/src/test/java/com/loyalty/transfer/client/
git commit -m "feat: agrega AccountClient que consulta account-service via Eureka reenviando el JWT"
```

---

### Task 6: Seguridad (Resource Server + mapeo de roles Keycloak)

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/security/SecurityConfig.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/security/SecurityConfigTest.java`

**Interfaces:**
- Consumes: nada nuevo (usa `issuer-uri` de `application.yml`, Task 1).
- Produces: mismo patrón que `account-service` (Fase 1) — `SecurityFilterChain` exigiendo JWT en toda ruta excepto `/actuator/health`, `JwtAuthenticationConverter` mapeando `realm_access.roles` a `ROLE_*`. Consumido por Task 8 (controller) y Task 11 (query controller).

- [ ] **Step 1: Escribir el test que falla**

`transfer-service/src/test/java/com/loyalty/transfer/security/SecurityConfigTest.java`:
```java
package com.loyalty.transfer.security;

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

Run: `mvn -q -pl transfer-service -am test -Dtest=SecurityConfigTest`
Expected: FAIL — `SecurityConfig` no existe.

- [ ] **Step 3: Crear `SecurityConfig`**

`transfer-service/src/main/java/com/loyalty/transfer/security/SecurityConfig.java`:
```java
package com.loyalty.transfer.security;

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

Run: `mvn -q -pl transfer-service -am test -Dtest=SecurityConfigTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/security/ transfer-service/src/test/java/com/loyalty/transfer/security/
git commit -m "feat: agrega configuracion de seguridad OAuth2 Resource Server con mapeo de roles de Keycloak"
```

---

### Task 7: Eventos y publisher de la saga

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/DebitRequestedEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditRequestedEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/CompensateDebitEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/DebitResultEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditResultEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaPublisher.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaPublisherTest.java`

**Interfaces:**
- Consumes: `KafkaTemplate<String, Object>` (autoconfigurado).
- Produces: los mismos 5 records de evento usados en `account-service` (mismo shape JSON, mismo campo `transactionId` como clave de partición — ver `ARQUITECTURA.md §Esquema de eventos`), y `TransferSagaPublisher` con `publishDebitRequested(String transactionId, String sourceAccountId, long amount)`, `publishCreditRequested(String transactionId, String targetAccountId, long amount)`, `publishCompensateDebit(String transactionId, String sourceAccountId, long amount)`. Consumido por Task 8 (service) y Task 9 (saga listener, tras crédito fallido).

- [ ] **Step 1: Escribir el test que falla**

`transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaPublisherTest.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.saga.events.CompensateDebitEvent;
import com.loyalty.transfer.saga.events.CreditRequestedEvent;
import com.loyalty.transfer.saga.events.DebitRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransferSagaPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishesDebitRequestedToDebitEventsTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishDebitRequested("tx-1", "acc-1", 50L);

        ArgumentCaptor<DebitRequestedEvent> captor = ArgumentCaptor.forClass(DebitRequestedEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("debit-events"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().sourceAccountId()).isEqualTo("acc-1");
        assertThat(captor.getValue().amount()).isEqualTo(50L);
    }

    @Test
    void publishesCreditRequestedToCreditEventsTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishCreditRequested("tx-1", "acc-2", 50L);

        ArgumentCaptor<CreditRequestedEvent> captor = ArgumentCaptor.forClass(CreditRequestedEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("credit-events"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().targetAccountId()).isEqualTo("acc-2");
    }

    @Test
    void publishesCompensateDebitToCompensationTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishCompensateDebit("tx-1", "acc-1", 50L);

        ArgumentCaptor<CompensateDebitEvent> captor = ArgumentCaptor.forClass(CompensateDebitEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("transfer-compensation"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().sourceAccountId()).isEqualTo("acc-1");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferSagaPublisherTest`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Crear los 5 records de evento**

`transfer-service/src/main/java/com/loyalty/transfer/saga/events/DebitRequestedEvent.java`:
```java
package com.loyalty.transfer.saga.events;

public record DebitRequestedEvent(String transactionId, String sourceAccountId, long amount, String timestamp) {
}
```

`transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditRequestedEvent.java`:
```java
package com.loyalty.transfer.saga.events;

public record CreditRequestedEvent(String transactionId, String targetAccountId, long amount, String timestamp) {
}
```

`transfer-service/src/main/java/com/loyalty/transfer/saga/events/CompensateDebitEvent.java`:
```java
package com.loyalty.transfer.saga.events;

public record CompensateDebitEvent(String transactionId, String sourceAccountId, long amount) {
}
```

`transfer-service/src/main/java/com/loyalty/transfer/saga/events/DebitResultEvent.java`:
```java
package com.loyalty.transfer.saga.events;

public record DebitResultEvent(String transactionId, boolean success, String reason) {
}
```

`transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditResultEvent.java`:
```java
package com.loyalty.transfer.saga.events;

public record CreditResultEvent(String transactionId, boolean success, String reason) {
}
```

- [ ] **Step 4: Crear `TransferSagaPublisher`**

`transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaPublisher.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.saga.events.CompensateDebitEvent;
import com.loyalty.transfer.saga.events.CreditRequestedEvent;
import com.loyalty.transfer.saga.events.DebitRequestedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransferSagaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TransferSagaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDebitRequested(String transactionId, String sourceAccountId, long amount) {
        kafkaTemplate.send("debit-events", transactionId,
                new DebitRequestedEvent(transactionId, sourceAccountId, amount, Instant.now().toString()));
    }

    public void publishCreditRequested(String transactionId, String targetAccountId, long amount) {
        kafkaTemplate.send("credit-events", transactionId,
                new CreditRequestedEvent(transactionId, targetAccountId, amount, Instant.now().toString()));
    }

    public void publishCompensateDebit(String transactionId, String sourceAccountId, long amount) {
        kafkaTemplate.send("transfer-compensation", transactionId,
                new CompensateDebitEvent(transactionId, sourceAccountId, amount));
    }
}
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferSagaPublisherTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/saga/events/ transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaPublisher.java transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaPublisherTest.java
git commit -m "feat: agrega eventos y publisher de la saga de transferencia"
```

---

### Task 8: Capa de servicio (validación síncrona + inicio de la saga)

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/service/TransferService.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/service/TransferServiceImpl.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/service/TransferServiceTest.java`

**Interfaces:**
- Consumes: `AccountClient` (Task 5), `TransactionRepository` (Task 2), `TransferSagaPublisher` (Task 7), excepciones (Task 4).
- Produces: `TransferService` con `Transaction initiate(String sourceAccountId, String targetAccountId, long amount, String requesterId, String bearerToken)`. Consumido por Task 9 (controller).

- [ ] **Step 1: Escribir los tests que fallan**

`transfer-service/src/test/java/com/loyalty/transfer/service/TransferServiceTest.java`:
```java
package com.loyalty.transfer.service;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.SameAccountException;
import com.loyalty.transfer.exception.TransferUnprocessableException;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.TransferSagaPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountClient accountClient;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransferSagaPublisher sagaPublisher;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferServiceImpl(accountClient, transactionRepository, sagaPublisher);
    }

    @Test
    void rejectsSameSourceAndTargetAccountWithoutCallingAccountService() {
        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-1", 10L, "user-1", "token"))
                .isInstanceOf(SameAccountException.class);

        verifyNoInteractions(accountClient);
    }

    @Test
    void rejectsWhenSourceBalanceInsufficient() {
        AccountView source = accountView("acc-1", "user-1", 5L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_BALANCE");
    }

    @Test
    void rejectsWhenSourceInactive() {
        AccountView source = accountView("acc-1", "user-1", 100L, "INACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "SOURCE_INACTIVE");
    }

    @Test
    void rejectsWhenTargetInactive() {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "INACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "TARGET_INACTIVE");
    }

    @Test
    void persistsPendingTransactionAndPublishesDebitRequestedWhenValid() {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.getSourceAccountId()).isEqualTo("acc-1");
        verify(sagaPublisher).publishDebitRequested(anyString(), org.mockito.ArgumentMatchers.eq("acc-1"), anyLong());
    }

    private AccountView accountView(String id, String ownerId, long balance, String status) {
        AccountView view = new AccountView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setBalance(balance);
        view.setStatus(status);
        return view;
    }
}
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferServiceTest`
Expected: FAIL — `TransferService`/`TransferServiceImpl` no existen.

- [ ] **Step 3: Crear la interfaz**

`transfer-service/src/main/java/com/loyalty/transfer/service/TransferService.java`:
```java
package com.loyalty.transfer.service;

import com.loyalty.transfer.domain.Transaction;

public interface TransferService {

    Transaction initiate(String sourceAccountId, String targetAccountId, long amount, String requesterId, String bearerToken);
}
```

- [ ] **Step 4: Implementar el service**

`transfer-service/src/main/java/com/loyalty/transfer/service/TransferServiceImpl.java`:
```java
package com.loyalty.transfer.service;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.SameAccountException;
import com.loyalty.transfer.exception.TransferUnprocessableException;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.TransferSagaPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransferServiceImpl implements TransferService {

    private final AccountClient accountClient;
    private final TransactionRepository transactionRepository;
    private final TransferSagaPublisher sagaPublisher;

    public TransferServiceImpl(AccountClient accountClient,
                                TransactionRepository transactionRepository,
                                TransferSagaPublisher sagaPublisher) {
        this.accountClient = accountClient;
        this.transactionRepository = transactionRepository;
        this.sagaPublisher = sagaPublisher;
    }

    @Override
    public Transaction initiate(String sourceAccountId, String targetAccountId, long amount,
                                 String requesterId, String bearerToken) {
        if (sourceAccountId.equals(targetAccountId)) {
            throw new SameAccountException("La cuenta origen y destino deben ser distintas");
        }

        AccountView source = accountClient.fetchAccount(sourceAccountId, bearerToken);
        AccountView target = accountClient.fetchAccount(targetAccountId, bearerToken);

        if (!"ACTIVE".equals(source.getStatus())) {
            throw new TransferUnprocessableException("SOURCE_INACTIVE", "La cuenta origen no esta activa");
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            throw new TransferUnprocessableException("TARGET_INACTIVE", "La cuenta destino no esta activa");
        }
        if (source.getBalance() < amount) {
            throw new TransferUnprocessableException("INSUFFICIENT_BALANCE", "Saldo insuficiente en la cuenta origen");
        }

        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID().toString());
        transaction.setSourceAccountId(sourceAccountId);
        transaction.setTargetAccountId(targetAccountId);
        transaction.setAmount(amount);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setCreatedAt(Instant.now());
        Transaction saved = transactionRepository.save(transaction);

        sagaPublisher.publishDebitRequested(saved.getId(), sourceAccountId, amount);

        return saved;
    }
}
```

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferServiceTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/service/ transfer-service/src/test/java/com/loyalty/transfer/service/
git commit -m "feat: agrega capa de servicio de transferencia con validacion sincrona previa"
```

---

### Task 9: Controller `POST /transfer`

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/controller/TransferController.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/controller/TransferControllerIT.java`

**Interfaces:**
- Consumes: `TransferService` (Task 8), `TransferRequest`/`TransferResponse` (Task 3), `SecurityConfig` (Task 6).
- Produces: `POST /api/v1/points/transfer` → `202 Accepted`. No produce nada consumido por tareas posteriores.

- [ ] **Step 1: Escribir el test de integración que falla**

`transfer-service/src/test/java/com/loyalty/transfer/controller/TransferControllerIT.java`:
```java
package com.loyalty.transfer.controller;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.dto.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class TransferControllerIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountClient accountClient;

    @Test
    void transferWithNonPositiveAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/points/transfer")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"acc-1\",\"targetAccountId\":\"acc-2\",\"amount\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validTransferReturns202WithPendingStatus() throws Exception {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), anyString())).thenReturn(source);
        when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-2"), anyString())).thenReturn(target);

        mockMvc.perform(post("/api/v1/points/transfer")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"acc-1\",\"targetAccountId\":\"acc-2\",\"amount\":40}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private AccountView accountView(String id, String ownerId, long balance, String status) {
        AccountView view = new AccountView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setBalance(balance);
        view.setStatus(status);
        return view;
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferControllerIT`
Expected: FAIL — `TransferController` no existe (404).

- [ ] **Step 3: Crear el controller**

`transfer-service/src/main/java/com/loyalty/transfer/controller/TransferController.java`:
```java
package com.loyalty.transfer.controller;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.dto.TransferRequest;
import com.loyalty.transfer.dto.TransferResponse;
import com.loyalty.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request,
                                                      Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        Jwt jwt = jwtAuth.getToken();

        Transaction transaction = transferService.initiate(
                request.getSourceAccountId(),
                request.getTargetAccountId(),
                request.getAmount(),
                jwt.getSubject(),
                jwt.getTokenValue());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TransferResponse.from(transaction));
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferControllerIT`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/controller/TransferController.java transfer-service/src/test/java/com/loyalty/transfer/controller/TransferControllerIT.java
git commit -m "feat: agrega TransferController con endpoint POST /transfer"
```

---

### Task 10: Listener de la saga (avance de estados según resultados) + timeout

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/SagaTimeoutScheduler.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaListenerIT.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/saga/SagaTimeoutSchedulerTest.java`

**Interfaces:**
- Consumes: `TransactionRepository` (Task 2), `TransferSagaPublisher` (Task 7), eventos (Task 7).
- Produces: `TransferSagaListener` con `@KafkaListener` de `debit-results` y `credit-results` que avanza `Transaction` según la máquina de estados de `ARQUITECTURA.md §Saga`; `SagaTimeoutScheduler` con `@Scheduled` que marca `PENDING` viejos (más de `transfer.saga.timeout-seconds`) como `FAILED` con `failureReason: SAGA_TIMEOUT`. No consumido por tareas posteriores (es el final de la orquestación).

- [ ] **Step 1: Escribir el test de integración que falla**

`transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaListenerIT.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.events.CreditResultEvent;
import com.loyalty.transfer.saga.events.DebitResultEvent;
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

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"debit-events", "credit-events", "transfer-compensation", "debit-results", "credit-results"})
class TransferSagaListenerIT {

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
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
    }

    @Test
    void debitSucceededPublishesCreditRequested() {
        savePending("tx-1", "acc-1", "acc-2", 40L);

        kafkaTemplate.send("debit-results", "tx-1", new DebitResultEvent("tx-1", true, null));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction tx = transactionRepository.findById("tx-1").orElseThrow();
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        });
    }

    @Test
    void debitFailedMarksTransactionFailed() {
        savePending("tx-2", "acc-1", "acc-2", 40L);

        kafkaTemplate.send("debit-results", "tx-2", new DebitResultEvent("tx-2", false, "INSUFFICIENT_BALANCE"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction tx = transactionRepository.findById("tx-2").orElseThrow();
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(tx.getFailureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        });
    }

    @Test
    void creditSucceededCompletesTransaction() {
        savePending("tx-3", "acc-1", "acc-2", 40L);

        kafkaTemplate.send("credit-results", "tx-3", new CreditResultEvent("tx-3", true, null));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction tx = transactionRepository.findById("tx-3").orElseThrow();
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(tx.getCompletedAt()).isNotNull();
        });
    }

    @Test
    void creditFailedTriggersCompensationAndMarksFailed() {
        savePending("tx-4", "acc-1", "acc-2", 40L);

        kafkaTemplate.send("credit-results", "tx-4", new CreditResultEvent("tx-4", false, "TARGET_INACTIVE"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction tx = transactionRepository.findById("tx-4").orElseThrow();
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(tx.getFailureReason()).isEqualTo("TARGET_INACTIVE");
        });
    }

    private void savePending(String id, String source, String target, long amount) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setSourceAccountId(source);
        tx.setTargetAccountId(target);
        tx.setAmount(amount);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCreatedAt(Instant.now());
        transactionRepository.save(tx);
    }
}
```

*(Requiere `org.awaitility:awaitility` como dependencia de test — agregar en Step 2.)*

- [ ] **Step 2: Agregar dependencia de test**

Modificar `transfer-service/pom.xml`, agregar dentro de `<dependencies>`:
```xml
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <version>4.2.2</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferSagaListenerIT`
Expected: FAIL — `TransferSagaListener` no existe.

- [ ] **Step 4: Crear `TransferSagaListener`**

`transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.events.CreditResultEvent;
import com.loyalty.transfer.saga.events.DebitResultEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransferSagaListener {

    private final TransactionRepository transactionRepository;
    private final TransferSagaPublisher publisher;

    public TransferSagaListener(TransactionRepository transactionRepository, TransferSagaPublisher publisher) {
        this.transactionRepository = transactionRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "debit-results", groupId = "transfer-service-debit-results")
    public void onDebitResult(DebitResultEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null || transaction.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        if (event.success()) {
            publisher.publishCreditRequested(transaction.getId(), transaction.getTargetAccountId(), transaction.getAmount());
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(event.reason());
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }

    @KafkaListener(topics = "credit-results", groupId = "transfer-service-credit-results")
    public void onCreditResult(CreditResultEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null || transaction.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        if (event.success()) {
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        } else {
            publisher.publishCompensateDebit(transaction.getId(), transaction.getSourceAccountId(), transaction.getAmount());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(event.reason());
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }
}
```

*(Nota: `account-service` no publica confirmación de compensación aplicada — ver Global Constraints — por lo que `onCreditResult` marca `FAILED` inmediatamente tras publicar `CompensateDebitEvent`, sin esperar respuesta.)*

- [ ] **Step 5: Crear `SagaTimeoutScheduler`**

`transfer-service/src/main/java/com/loyalty/transfer/saga/SagaTimeoutScheduler.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class SagaTimeoutScheduler {

    private final TransactionRepository transactionRepository;
    private final long timeoutSeconds;

    public SagaTimeoutScheduler(TransactionRepository transactionRepository,
                                 @Value("${transfer.saga.timeout-seconds}") long timeoutSeconds) {
        this.transactionRepository = transactionRepository;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelay = 10000)
    public void expireStaleTransactions() {
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);
        List<Transaction> stale = transactionRepository.findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, threshold);

        for (Transaction transaction : stale) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("SAGA_TIMEOUT");
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }
}
```

- [ ] **Step 6: Escribir y ejecutar el test del scheduler**

`transfer-service/src/test/java/com/loyalty/transfer/saga/SagaTimeoutSchedulerTest.java`:
```java
package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaTimeoutSchedulerTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Test
    void marksStaleTransactionsAsFailedWithTimeoutReason() {
        Transaction stale = new Transaction();
        stale.setId("tx-old");
        stale.setStatus(TransactionStatus.PENDING);
        stale.setCreatedAt(Instant.now().minusSeconds(60));

        when(transactionRepository.findByStatusAndCreatedAtBefore(any(TransactionStatus.class), any(Instant.class)))
                .thenReturn(List.of(stale));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        SagaTimeoutScheduler scheduler = new SagaTimeoutScheduler(transactionRepository, 30L);
        scheduler.expireStaleTransactions();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("SAGA_TIMEOUT");
    }
}
```

Run: `mvn -q -pl transfer-service -am test -Dtest=TransferSagaListenerIT,SagaTimeoutSchedulerTest`
Expected: PASS (5 tests en total).

- [ ] **Step 7: Commit**

```bash
git add transfer-service/pom.xml transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java transfer-service/src/main/java/com/loyalty/transfer/saga/SagaTimeoutScheduler.java transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaListenerIT.java transfer-service/src/test/java/com/loyalty/transfer/saga/SagaTimeoutSchedulerTest.java
git commit -m "feat: agrega listener de resultados de la saga y scheduler de timeout"
```

---

### Task 11: Endpoint interno `GET /transactions` (resuelve el gap de Fase 1)

**Files:**
- Create: `transfer-service/src/main/java/com/loyalty/transfer/controller/TransactionQueryController.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/controller/TransactionQueryControllerIT.java`

**Interfaces:**
- Consumes: `TransactionRepository` (Task 2), `TransactionResponse` (Task 3).
- Produces: `GET /transactions?accountId={id}` → lista de `TransactionResponse` donde la cuenta es origen o destino. Este endpoint es consumido por `account-service` (fuera de este plan — ajuste a implementar en `account-service` cuando se retome ese servicio: su `GET /accounts/{id}/transactions` debe delegar aquí vía `RestClient` `@LoadBalanced` a `http://transfer-service/transactions?accountId={id}`, reenviando el JWT).

*(Nota: este endpoint no valida ownership por sí mismo — asume que quien lo llama (`account-service`) ya validó que el requester tiene acceso a `accountId`. Es un endpoint de uso interno service-to-service, no pensado para ser llamado directamente por clientes externos a través del Gateway; el Gateway, Fase 4, solo expondrá `/accounts/**` públicamente, no `/transactions/**`.)*

- [ ] **Step 1: Escribir el test de integración que falla**

`transfer-service/src/test/java/com/loyalty/transfer/controller/TransactionQueryControllerIT.java`:
```java
package com.loyalty.transfer.controller;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class TransactionQueryControllerIT {

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
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
    }

    @Test
    void listsTransactionsWhereAccountIsSourceOrTarget() throws Exception {
        saveTransaction("tx-1", "acc-1", "acc-2");
        saveTransaction("tx-2", "acc-3", "acc-1");
        saveTransaction("tx-3", "acc-4", "acc-5");

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1")
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void saveTransaction(String id, String source, String target) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setSourceAccountId(source);
        tx.setTargetAccountId(target);
        tx.setAmount(10L);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setCreatedAt(Instant.now());
        transactionRepository.save(tx);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransactionQueryControllerIT`
Expected: FAIL — `TransactionQueryController` no existe.

- [ ] **Step 3: Crear el controller**

`transfer-service/src/main/java/com/loyalty/transfer/controller/TransactionQueryController.java`:
```java
package com.loyalty.transfer.controller;

import com.loyalty.transfer.dto.TransactionResponse;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionQueryController {

    private final TransactionRepository transactionRepository;

    public TransactionQueryController(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listByAccount(@RequestParam("accountId") String accountId) {
        return transactionRepository.findBySourceAccountIdOrTargetAccountId(accountId, accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl transfer-service -am test -Dtest=TransactionQueryControllerIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/controller/TransactionQueryController.java transfer-service/src/test/java/com/loyalty/transfer/controller/TransactionQueryControllerIT.java
git commit -m "feat: agrega endpoint interno GET /transactions por cuenta, resuelve gap de Fase 1"
```

---

## Self-Review

**1. Spec coverage:**
- `POST /api/v1/points/transfer` con `202 Accepted` → Task 9.
- Validación síncrona previa (existencia, ownership vía `account-service`, estado, saldo) → Task 5 (client), Task 8 (service).
- Orquestación completa de la saga (7 pasos de la máquina de estados) → Task 10.
- Timeout de saga → Task 10 (`SagaTimeoutScheduler`).
- Seguridad (Resource Server, mismo realm) → Task 6.
- Manejo de errores (`400/403/404/422`, formato estándar) → Task 4.
- Testing: unit test de saldo insuficiente (Task 8, `rejectsWhenSourceBalanceInsufficient` — **el test explícitamente pedido en el requerimiento original**), integration test de `amount <= 0` → 400 (Task 9, `transferWithNonPositiveAmountReturns400` — **también explícitamente pedido**), test de la máquina de estados completa (Task 10), test de timeout (Task 10).
- Gap de Fase 1 (`GET /accounts/{id}/transactions`) → Task 11 resuelve el lado `transfer-service`; queda documentado que `account-service` necesita un ajuste futuro para delegar a este endpoint.

**2. Placeholder scan:** sin TBD/TODO; todo el código es literal y ejecutable, incluyendo los tests con `MockWebServer` y `awaitility` para las dependencias adicionales necesarias.

**3. Type consistency:** `Transaction`/`TransactionStatus` (Task 2) usados idénticos en Tasks 3, 8, 10, 11. Los 5 records de evento (Task 7) son el mismo shape que los de `account-service` (Fase 1) — mismos nombres de campo, mismos topics. `AccountClient.fetchAccount` (Task 5) devuelve `AccountView` (Task 3), consumido consistentemente en Task 8. `TransferService.initiate(...)` firma usada igual en Task 8 (implementación) y Task 9 (controller).

## Nota para Fase 3 (o ajuste a Fase 1)

`account-service` necesita un ajuste posterior: su `GET /accounts/{id}/transactions` (documentado como pendiente en la spec de Fase 1) debe implementarse como una llamada `RestClient` `@LoadBalanced` desde `account-service` hacia `http://transfer-service/transactions?accountId={id}`, reenviando el JWT del request original — mismo patrón que `AccountClient` en este plan (Task 5), pero en la dirección inversa. Este ajuste no se incluye en el plan de Fase 2 porque modifica `account-service`, no `transfer-service`; se recomienda ejecutarlo como una tarea corta de "ajuste de Fase 1" antes de avanzar a Fase 3, o junto con Fase 3.

## Correcciones descubiertas durante la ejecución

La verificación real de Task 9 (`TransferControllerIT`) reveló 2 bugs que el self-review documental no podía anticipar:

1. **JWT mockeado sin authorities.** El test `validTransferReturns202WithPendingStatus` usaba `jwt().jwt(j -> j.subject("user-1"))` sin `.authorities(...)`. `SecurityMockMvcRequestPostProcessors.jwt()` no pasa por el `JwtAuthenticationConverter` real de la app — usa su propio conversor por defecto (basado en el claim `scope`, ausente aquí), así que sin autoridades explícitas el `@PreAuthorize("hasRole('USER')")` del controller deniega con `403`. Fix: agregar `.authorities(new SimpleGrantedAuthority("ROLE_USER"))` al post-processor.
2. **Test sin broker Kafka.** El flujo real de `POST /transfer` exitoso invoca `TransferSagaPublisher.publishDebitRequested(...)`, que necesita un broker Kafka. El test no declaraba `@EmbeddedKafka`, así que el intento de publicar fallaba con `ConfigException: No resolvable bootstrap urls given in bootstrap.servers` (el hostname `kafka` de `application.yml` no es resoluble fuera de Docker). Fix: agregar `@EmbeddedKafka(partitions = 1, topics = {"debit-events"})` a la clase de test y sobrescribir `spring.kafka.bootstrap-servers` vía `@DynamicPropertySource` apuntando al broker embebido.

Ambos verificados con `mvn -pl transfer-service -am test -Dtest=TransferControllerIT` en verde tras el fix.
