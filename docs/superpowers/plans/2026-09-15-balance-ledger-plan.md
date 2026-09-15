# Balance Ledger (Kardex de auditoría) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar un kardex de auditoría (`balance_ledger`) a `account-service`: cada movimiento de saldo queda registrado con saldo antes/después, dentro de una transacción Mongo junto al cambio real; `POST /accounts` deja de aceptar saldo inicial; se agrega un piso configurable (`minBalance`) por cuenta; y una cuenta de tesorería (`acc-treasury`) se siembra al arranque para que todo fondeo inicial sea una transferencia real y trazable.

**Architecture:** Se extiende `account-service` (Fase 1, ya en `develop`) — no se crea ningún servicio nuevo. `AccountAtomicOperationsImpl` pasa de aplicar solo un `$inc` a aplicar `$inc` + insertar una línea en `balance_ledger` dentro de una `ClientSession` transaccional de MongoDB. Un `ApplicationRunner` siembra `acc-treasury` al arrancar.

**Tech Stack:** Java 21, Spring Data MongoDB (`MongoTransactionManager`, `MongoTemplate` con `ClientSession`), MongoDB replica-set (ya existe desde Fase 0 — requisito para transacciones multi-documento).

**Spec:** `docs/superpowers/specs/2026-09-15-balance-ledger-design.md` (contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- `minBalance: Long` (nullable, default `0`) en `Account` — el piso configurable (D2 de la spec). `null` significa sin piso (solo válido para cuentas administradas explícitamente, ej. `acc-treasury`).
- Condición de débito: `balance - amount >= minBalance` (tratando `minBalance == null` como "sin piso", es decir, la condición siempre se cumple del lado del piso).
- `balance_ledger` nunca duplica `amount`/`status` de `Transaction` (`transfer-service`) — solo referencia `transactionId` (D5 de la spec).
- El `$inc` de saldo y el `insert` en `balance_ledger` se hacen dentro de la misma transacción Mongo (D4 de la spec) — nunca se desincronizan.
- `POST /accounts` ya no acepta `balance` — toda cuenta nace en `0` (D3 de la spec).
- `acc-treasury` se siembra por código (`ApplicationRunner`), no por script externo — con `minBalance: null`.
- `PATCH /accounts/{id}/limits` y la creación/consulta de `acc-treasury` requieren rol `ADMIN` para modificar `minBalance` (D1 de la spec).

---

## File Structure

```
account-service/src/main/java/com/loyalty/account/
├── domain/
│   ├── Account.java                          # MODIFICAR: agregar minBalance
│   ├── BalanceLedgerEntry.java                 # NUEVO
│   └── BalanceLedgerEventType.java              # NUEVO (SEED, DEBIT, CREDIT, COMPENSATION)
├── repository/
│   ├── AccountAtomicOperations.java            # MODIFICAR: firmas con transactionId
│   ├── AccountAtomicOperationsImpl.java         # MODIFICAR: transaccion + minBalance + ledger
│   └── BalanceLedgerRepository.java              # NUEVO
├── dto/
│   ├── CreateAccountRequest.java                # MODIFICAR: quitar balance
│   ├── AccountResponse.java                      # MODIFICAR: agregar minBalance
│   ├── UpdateLimitsRequest.java                   # NUEVO
│   └── BalanceLedgerEntryResponse.java             # NUEVO
├── service/
│   ├── AccountService.java                        # MODIFICAR: create sin balance, updateMinBalance
│   └── AccountServiceImpl.java                     # MODIFICAR
├── controller/
│   └── AccountController.java                      # MODIFICAR: GET .../ledger, PATCH .../limits
├── saga/
│   └── SagaEventListener.java                       # MODIFICAR: pasar transactionId
├── config/
│   └── MongoTransactionConfig.java                    # NUEVO: bean MongoTransactionManager
└── TreasurySeedRunner.java                             # NUEVO: ApplicationRunner
```

---

### Task 1: `Account.minBalance`, `CreateAccountRequest` sin `balance`, `AccountResponse.minBalance`

**Files:**
- Modify: `account-service/src/main/java/com/loyalty/account/domain/Account.java`
- Modify: `account-service/src/main/java/com/loyalty/account/dto/CreateAccountRequest.java`
- Modify: `account-service/src/main/java/com/loyalty/account/dto/AccountResponse.java`
- Modify: `account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java`
- Test: `account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `Account`, `AccountStatus` (ya existentes, Fase 1).
- Produces: `Account.getMinBalance()`/`setMinBalance(Long)`. `AccountService.create(String ownerId)` (firma cambia — **ya no recibe `initialBalance`**, ver Global Constraints). `AccountResponse` incluye `minBalance`. Consumido por Task 3 (operaciones atómicas leen `minBalance`) y Task 6 (endpoint de creación).

- [ ] **Step 1: Actualizar el test existente que rompe por el cambio de firma**

`account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java` — reemplazar los dos tests de creación:
```java
    @Test
    void createPersistsAccountWithOwnerActiveStatusAndZeroBalance() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account created = accountService.create("user-1");

        assertThat(created.getOwnerId()).isEqualTo("user-1");
        assertThat(created.getBalance()).isZero();
        assertThat(created.getMinBalance()).isZero();
        assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).startsWith("acc-");
    }
```
Eliminar el test `createDefaultsBalanceToZeroWhenNull` (ya no aplica — ahora **siempre** es cero, no hay valor a defaultear).

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: FAIL — `accountService.create("user-1")` no compila (la firma actual recibe 2 argumentos), y `getMinBalance()` no existe.

- [ ] **Step 3: Agregar `minBalance` a `Account`**

Modificar `account-service/src/main/java/com/loyalty/account/domain/Account.java`, agregar el campo y su getter/setter:
```java
    private Long minBalance;

    public Long getMinBalance() {
        return minBalance;
    }

    public void setMinBalance(Long minBalance) {
        this.minBalance = minBalance;
    }
```

- [ ] **Step 4: Quitar `balance` de `CreateAccountRequest`**

Reemplazar `account-service/src/main/java/com/loyalty/account/dto/CreateAccountRequest.java`:
```java
package com.loyalty.account.dto;

public class CreateAccountRequest {
    // Intencionalmente vacio: toda cuenta nueva nace en balance 0 (ver spec D3).
    // Se mantiene como clase (no se elimina el parametro @RequestBody del controller)
    // para no romper el contrato HTTP si en el futuro se agregan otros campos de creacion.
}
```

- [ ] **Step 5: Agregar `minBalance` a `AccountResponse`**

Modificar `account-service/src/main/java/com/loyalty/account/dto/AccountResponse.java`:
```java
    private Long minBalance;
```
Y en `from(Account account)`, agregar:
```java
        response.minBalance = account.getMinBalance();
```
Y el getter:
```java
    public Long getMinBalance() {
        return minBalance;
    }
```

- [ ] **Step 6: Actualizar `AccountService`/`AccountServiceImpl.create`**

`account-service/src/main/java/com/loyalty/account/service/AccountService.java` — cambiar la firma:
```java
    Account create(String ownerId);
```

`account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java` — reemplazar el método `create`:
```java
    @Override
    public Account create(String ownerId) {
        Account account = new Account();
        account.setId("acc-" + UUID.randomUUID());
        account.setOwnerId(ownerId);
        account.setBalance(0L);
        account.setMinBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }
```

- [ ] **Step 7: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: PASS (6 tests — los 2 de creación reemplazados por 1, los otros 5 sin cambios).

- [ ] **Step 8: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/domain/Account.java account-service/src/main/java/com/loyalty/account/dto/CreateAccountRequest.java account-service/src/main/java/com/loyalty/account/dto/AccountResponse.java account-service/src/main/java/com/loyalty/account/service/ account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java
git commit -m "feat: agrega minBalance a Account y quita balance de CreateAccountRequest"
```

---

### Task 2: Dominio del ledger (`BalanceLedgerEntry`, `BalanceLedgerEventType`, `BalanceLedgerRepository`)

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEventType.java`
- Create: `account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEntry.java`
- Create: `account-service/src/main/java/com/loyalty/account/repository/BalanceLedgerRepository.java`
- Test: `account-service/src/test/java/com/loyalty/account/repository/BalanceLedgerRepositoryIT.java`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `BalanceLedgerEntry{ id, accountId, eventType, transactionId, delta, balanceBefore, balanceAfter, createdAt }` con constructor `BalanceLedgerEntry(String accountId, BalanceLedgerEventType eventType, String transactionId, long delta, long balanceBefore, long balanceAfter)` (autogenera `id` y `createdAt`). `BalanceLedgerRepository extends MongoRepository<BalanceLedgerEntry, String>` con `List<BalanceLedgerEntry> findByAccountIdOrderByCreatedAtAsc(String accountId)` y `long countByAccountId(String accountId)` (usado en Task 3 para distinguir `SEED` de `CREDIT`). Consumido por Task 3 (escritura) y Task 6 (endpoint de lectura).

- [ ] **Step 1: Escribir el test de integración que falla**

`account-service/src/test/java/com/loyalty/account/repository/BalanceLedgerRepositoryIT.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
class BalanceLedgerRepositoryIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private BalanceLedgerRepository ledgerRepository;

    @Test
    void savesAndListsEntriesOrderedByCreationAscending() throws InterruptedException {
        ledgerRepository.save(new BalanceLedgerEntry("acc-1", BalanceLedgerEventType.DEBIT, "tx-1", -40L, 100L, 60L));
        Thread.sleep(5);
        ledgerRepository.save(new BalanceLedgerEntry("acc-1", BalanceLedgerEventType.CREDIT, "tx-2", 20L, 60L, 80L));

        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-1");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getBalanceAfter()).isEqualTo(60L);
        assertThat(entries.get(1).getBalanceAfter()).isEqualTo(80L);
        assertThat(ledgerRepository.countByAccountId("acc-1")).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=BalanceLedgerRepositoryIT`
Expected: FAIL — las clases no existen.

- [ ] **Step 3: Crear `BalanceLedgerEventType`**

`account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEventType.java`:
```java
package com.loyalty.account.domain;

public enum BalanceLedgerEventType {
    SEED,
    DEBIT,
    CREDIT,
    COMPENSATION
}
```

- [ ] **Step 4: Crear `BalanceLedgerEntry`**

`account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEntry.java`:
```java
package com.loyalty.account.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document(collection = "balance_ledger")
public class BalanceLedgerEntry {

    @Id
    private String id;
    private String accountId;
    private BalanceLedgerEventType eventType;
    private String transactionId;
    private long delta;
    private long balanceBefore;
    private long balanceAfter;
    private Instant createdAt;

    protected BalanceLedgerEntry() {
    }

    public BalanceLedgerEntry(String accountId, BalanceLedgerEventType eventType, String transactionId,
                               long delta, long balanceBefore, long balanceAfter) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.eventType = eventType;
        this.transactionId = transactionId;
        this.delta = delta;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BalanceLedgerEventType getEventType() {
        return eventType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceBefore() {
        return balanceBefore;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 5: Crear `BalanceLedgerRepository`**

`account-service/src/main/java/com/loyalty/account/repository/BalanceLedgerRepository.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.BalanceLedgerEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BalanceLedgerRepository extends MongoRepository<BalanceLedgerEntry, String> {

    List<BalanceLedgerEntry> findByAccountIdOrderByCreatedAtAsc(String accountId);

    long countByAccountId(String accountId);
}
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=BalanceLedgerRepositoryIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEventType.java account-service/src/main/java/com/loyalty/account/domain/BalanceLedgerEntry.java account-service/src/main/java/com/loyalty/account/repository/BalanceLedgerRepository.java account-service/src/test/java/com/loyalty/account/repository/BalanceLedgerRepositoryIT.java
git commit -m "feat: agrega dominio y repositorio del balance_ledger"
```

---

### Task 3: `AccountAtomicOperationsImpl` — `minBalance` + transacción Mongo + escritura del ledger

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/config/MongoTransactionConfig.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`
- Modify: `account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java`

**Interfaces:**
- Consumes: `Account`, `BalanceLedgerEntry`, `BalanceLedgerEventType`, `BalanceLedgerRepository` (Task 2).
- Produces: `AccountAtomicOperations` con las firmas **modificadas**: `boolean debitIfSufficientBalance(String accountId, long amount, String transactionId)`, `boolean creditIfActive(String accountId, long amount, String transactionId)`, `void creditUnconditionally(String accountId, long amount, String transactionId)` — todas ahora reciben `transactionId` para poblar el ledger, y todas escriben una línea en `balance_ledger` atómicamente junto al cambio de saldo. Consumido por Task 4 (`SagaEventListener` ya actualizado en este mismo Task) y Task 5 (seed de tesorería).

- [ ] **Step 1: Actualizar el test de integración con las nuevas firmas y aserciones de ledger**

Reemplazar `account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java` completo:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataMongoTest
@Import(AccountAtomicOperationsIT.TransactionManagerTestConfig.class)
class AccountAtomicOperationsIT {

    @TestConfiguration
    static class TransactionManagerTestConfig {
        @Bean
        MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
            return new MongoTransactionManager(dbFactory);
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceLedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        ledgerRepository.deleteAll();
    }

    @Test
    void debitSucceedsWhenBalanceStaysAtOrAboveMinBalance() {
        saveAccount("acc-1", 100L, 0L, AccountStatus.ACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-1", 40L, "tx-1");

        assertThat(result).isTrue();
        assertThat(accountRepository.findById("acc-1").orElseThrow().getBalance()).isEqualTo(60L);

        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-1");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEventType()).isEqualTo(BalanceLedgerEventType.DEBIT);
        assertThat(entries.get(0).getTransactionId()).isEqualTo("tx-1");
        assertThat(entries.get(0).getDelta()).isEqualTo(-40L);
        assertThat(entries.get(0).getBalanceBefore()).isEqualTo(100L);
        assertThat(entries.get(0).getBalanceAfter()).isEqualTo(60L);
    }

    @Test
    void debitFailsWhenResultWouldGoBelowMinBalance() {
        saveAccount("acc-2", 10L, 0L, AccountStatus.ACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-2", 40L, "tx-2");

        assertThat(result).isFalse();
        assertThat(accountRepository.findById("acc-2").orElseThrow().getBalance()).isEqualTo(10L);
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-2")).isEmpty();
    }

    @Test
    void debitSucceedsBelowZeroWhenMinBalanceIsNegative() {
        saveAccount("acc-treasury-test", 0L, -1000L, AccountStatus.ACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-treasury-test", 500L, "tx-3");

        assertThat(result).isTrue();
        assertThat(accountRepository.findById("acc-treasury-test").orElseThrow().getBalance()).isEqualTo(-500L);
    }

    @Test
    void debitFailsWhenAccountInactive() {
        saveAccount("acc-3", 100L, 0L, AccountStatus.INACTIVE);

        boolean result = accountRepository.debitIfSufficientBalance("acc-3", 10L, "tx-4");

        assertThat(result).isFalse();
    }

    @Test
    void firstCreditIsRecordedAsSeedEventType() {
        saveAccount("acc-4", 0L, 0L, AccountStatus.ACTIVE);

        boolean result = accountRepository.creditIfActive("acc-4", 100L, "tx-5");

        assertThat(result).isTrue();
        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-4");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEventType()).isEqualTo(BalanceLedgerEventType.SEED);
    }

    @Test
    void secondCreditIsRecordedAsCreditEventType() {
        saveAccount("acc-5", 0L, 0L, AccountStatus.ACTIVE);
        accountRepository.creditIfActive("acc-5", 100L, "tx-6");

        boolean result = accountRepository.creditIfActive("acc-5", 25L, "tx-7");

        assertThat(result).isTrue();
        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-5");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getEventType()).isEqualTo(BalanceLedgerEventType.CREDIT);
    }

    @Test
    void creditFailsWhenAccountInactive() {
        saveAccount("acc-6", 50L, 0L, AccountStatus.INACTIVE);

        boolean result = accountRepository.creditIfActive("acc-6", 25L, "tx-8");

        assertThat(result).isFalse();
    }

    @Test
    void creditUnconditionallyRestoresBalanceAndRecordsCompensation() {
        saveAccount("acc-7", 0L, 0L, AccountStatus.INACTIVE);

        accountRepository.creditUnconditionally("acc-7", 40L, "tx-9");

        assertThat(accountRepository.findById("acc-7").orElseThrow().getBalance()).isEqualTo(40L);
        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-7");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEventType()).isEqualTo(BalanceLedgerEventType.COMPENSATION);
    }

    @Test
    void concurrentDebitsNeverLeaveBalanceBelowMinBalance() throws InterruptedException {
        saveAccount("acc-concurrent", 100L, 0L, AccountStatus.ACTIVE);

        int attempts = 5;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch latch = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    if (accountRepository.debitIfSufficientBalance("acc-concurrent", 30L, "tx-concurrent-" + index)) {
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
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-concurrent")).hasSize(3);
    }

    private void saveAccount(String id, long balance, long minBalance, AccountStatus status) {
        Account account = new Account();
        account.setId(id);
        account.setOwnerId("owner");
        account.setBalance(balance);
        account.setMinBalance(minBalance);
        account.setStatus(status);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: FAIL — las firmas no coinciden (faltan `transactionId`), no existe `MongoTransactionConfig` en el código de producción, y el comportamiento de `minBalance`/`SEED` todavía no existe.

- [ ] **Step 3: Crear el bean `MongoTransactionManager`**

`account-service/src/main/java/com/loyalty/account/config/MongoTransactionConfig.java`:
```java
package com.loyalty.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

@Configuration
public class MongoTransactionConfig {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
```

- [ ] **Step 4: Actualizar la interfaz `AccountAtomicOperations`**

`account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`:
```java
package com.loyalty.account.repository;

public interface AccountAtomicOperations {

    boolean debitIfSufficientBalance(String accountId, long amount, String transactionId);

    boolean creditIfActive(String accountId, long amount, String transactionId);

    void creditUnconditionally(String accountId, long amount, String transactionId);
}
```

- [ ] **Step 5: Reescribir `AccountAtomicOperationsImpl`**

`account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`:
```java
package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public class AccountAtomicOperationsImpl implements AccountAtomicOperations {

    private final MongoTemplate mongoTemplate;
    private final BalanceLedgerRepository ledgerRepository;

    public AccountAtomicOperationsImpl(MongoTemplate mongoTemplate, BalanceLedgerRepository ledgerRepository) {
        this.mongoTemplate = mongoTemplate;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    @Transactional("transactionManager")
    public boolean debitIfSufficientBalance(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null || before.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }

        CriteriaDefinition minBalanceExpr = () -> new Document("$expr", new Document("$gte", List.of(
                new Document("$subtract", List.of("$balance", amount)),
                new Document("$ifNull", List.of("$minBalance", 0L))
        )));

        Query query = new Query(Criteria.where("_id").is(accountId).and("status").is(AccountStatus.ACTIVE));
        query.addCriteria(minBalanceExpr);
        Update update = new Update().inc("balance", -amount).set("updatedAt", Instant.now());

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            long balanceAfter = before.getBalance() - amount;
            ledgerRepository.save(new BalanceLedgerEntry(accountId, BalanceLedgerEventType.DEBIT, transactionId,
                    -amount, before.getBalance(), balanceAfter));
        }
        return applied;
    }

    @Override
    @Transactional("transactionManager")
    public boolean creditIfActive(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return false;
        }

        Query query = new Query(Criteria.where("_id").is(accountId).and("status").is(AccountStatus.ACTIVE));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            long balanceAfter = before.getBalance() + amount;
            boolean isFirstCredit = ledgerRepository.countByAccountId(accountId) == 0;
            BalanceLedgerEventType eventType = isFirstCredit ? BalanceLedgerEventType.SEED : BalanceLedgerEventType.CREDIT;
            ledgerRepository.save(new BalanceLedgerEntry(accountId, eventType, transactionId,
                    amount, before.getBalance(), balanceAfter));
        }
        return applied;
    }

    @Override
    @Transactional("transactionManager")
    public void creditUnconditionally(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return;
        }

        Query query = new Query(Criteria.where("_id").is(accountId));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        mongoTemplate.updateFirst(query, update, Account.class);

        long balanceAfter = before.getBalance() + amount;
        ledgerRepository.save(new BalanceLedgerEntry(accountId, BalanceLedgerEventType.COMPENSATION, transactionId,
                amount, before.getBalance(), balanceAfter));
    }
}
```

*(Nota: la lectura `before` dentro del mismo método `@Transactional` participa de la misma transacción/sesión Mongo que el `updateFirst` y el `ledgerRepository.save` posteriores — Spring Data MongoDB adjunta automáticamente la `ClientSession` activa a cualquier operación de `MongoTemplate`/`MongoRepository` ejecutada dentro del método anotado. La condición de concurrencia sigue garantizada por el filtro atómico `$expr` de `updateFirst`, no por la transacción — la transacción solo garantiza que el `insert` del ledger nunca quede desincronizado del `$inc`, sea cual sea el resultado.)*

- [ ] **Step 6: Actualizar `AccountRepository` (sin cambios de tipo, solo hereda las nuevas firmas)**

`account-service/src/main/java/com/loyalty/account/repository/AccountRepository.java` no necesita cambios de código — ya extiende `AccountAtomicOperations`, hereda las firmas nuevas automáticamente.

- [ ] **Step 7: Actualizar `SagaEventListener` para pasar `transactionId`**

Modificar `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`, las 3 llamadas:
```java
        boolean debited = accountRepository.debitIfSufficientBalance(event.sourceAccountId(), event.amount(), event.transactionId());
```
```java
        boolean credited = accountRepository.creditIfActive(event.targetAccountId(), event.amount(), event.transactionId());
```
```java
        accountRepository.creditUnconditionally(event.sourceAccountId(), event.amount(), event.transactionId());
```

- [ ] **Step 8: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: PASS (9 tests, incluyendo el de concurrencia y los de `SEED`/`CREDIT`/`COMPENSATION`).

- [ ] **Step 9: Ejecutar también `SagaEventListenerIT` (Fase 1) para confirmar que no rompió con el cambio de firma**

Run: `mvn -q -pl account-service -am test -Dtest=SagaEventListenerIT`
Expected: PASS (4 tests, sin cambios necesarios en ese archivo — solo consume `SagaEventListener`, que ya fue actualizado en el Step 7).

- [ ] **Step 10: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/config/ account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java
git commit -m "feat: minBalance configurable y escritura transaccional del balance_ledger en cada operacion atomica"
```

---

### Task 4: Siembra de `acc-treasury` al arranque

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/TreasurySeedRunner.java`
- Test: `account-service/src/test/java/com/loyalty/account/TreasurySeedRunnerIT.java`

**Interfaces:**
- Consumes: `AccountRepository` (Fase 1), `Account`, `AccountStatus` (Fase 1).
- Produces: al arrancar el contexto de Spring, existe una cuenta `_id = "acc-treasury"`, `ownerId = "system"`, `status = ACTIVE`, `balance = 0`, `minBalance = null`. No consumido por ninguna tarea posterior de este plan (es el final de la cadena de infraestructura) — sí lo usará cualquier transferencia de fondeo inicial hecha manualmente por un `ADMIN` después de este cambio.

- [ ] **Step 1: Escribir el test que falla**

`account-service/src/test/java/com/loyalty/account/TreasurySeedRunnerIT.java`:
```java
package com.loyalty.account;

import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class TreasurySeedRunnerIT {

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
    void treasuryAccountExistsAfterStartup() {
        assertThat(accountRepository.findById("acc-treasury")).isPresent();
        var treasury = accountRepository.findById("acc-treasury").orElseThrow();
        assertThat(treasury.getBalance()).isZero();
        assertThat(treasury.getMinBalance()).isNull();
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=TreasurySeedRunnerIT`
Expected: FAIL — `acc-treasury` no existe.

- [ ] **Step 3: Crear el `TreasurySeedRunner`**

`account-service/src/main/java/com/loyalty/account/TreasurySeedRunner.java`:
```java
package com.loyalty.account;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TreasurySeedRunner implements ApplicationRunner {

    private static final String TREASURY_ACCOUNT_ID = "acc-treasury";

    private final AccountRepository accountRepository;

    public TreasurySeedRunner(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.existsById(TREASURY_ACCOUNT_ID)) {
            return;
        }

        Account treasury = new Account();
        treasury.setId(TREASURY_ACCOUNT_ID);
        treasury.setOwnerId("system");
        treasury.setBalance(0L);
        treasury.setMinBalance(null);
        treasury.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        treasury.setCreatedAt(now);
        treasury.setUpdatedAt(now);
        accountRepository.save(treasury);
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=TreasurySeedRunnerIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/TreasurySeedRunner.java account-service/src/test/java/com/loyalty/account/TreasurySeedRunnerIT.java
git commit -m "feat: siembra la cuenta de tesoreria acc-treasury al arrancar account-service"
```

---

### Task 5: Endpoint `GET /accounts/{id}/ledger`

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/dto/BalanceLedgerEntryResponse.java`
- Modify: `account-service/src/main/java/com/loyalty/account/controller/AccountController.java`
- Modify: `account-service/src/test/java/com/loyalty/account/controller/AccountControllerIT.java`

**Interfaces:**
- Consumes: `BalanceLedgerRepository` (Task 2), `AccountService.getByIdForRequester` (Fase 1, reutilizado para validar ownership).
- Produces: `GET /accounts/{id}/ledger` → `200` con lista de `BalanceLedgerEntryResponse{ id, accountId, eventType, transactionId, delta, balanceBefore, balanceAfter, createdAt }`, autorización igual a `GET /accounts/{id}` (dueño o `ADMIN`). No consumido por tareas posteriores.

- [ ] **Step 1: Actualizar el test de integración existente que rompe por `POST /accounts` sin `balance`, y agregar el test del nuevo endpoint**

En `account-service/src/test/java/com/loyalty/account/controller/AccountControllerIT.java`, reemplazar el test `createAccountReturns201WithOwnerFromJwt`:
```java
    @Test
    void createAccountReturns201WithOwnerFromJwtAndZeroBalance() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("user-1"))
                .andExpect(jsonPath("$.balance").value(0));
    }
```

Agregar el nuevo test al final de la clase:
```java
    @Test
    void getLedgerReturnsEntriesForOwner() throws Exception {
        Account account = new Account();
        account.setId("acc-ledger-test");
        account.setOwnerId("user-1");
        account.setBalance(100L);
        account.setMinBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        accountRepository.creditIfActive("acc-ledger-test", 100L, "tx-seed-test");

        mockMvc.perform(get("/accounts/acc-ledger-test/ledger")
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("SEED"))
                .andExpect(jsonPath("$[0].balanceAfter").value(200));
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountControllerIT`
Expected: FAIL — `createAccountReturns201...` falla porque el controller todavía usa el `balance` del body (Task 1 ya lo quitó del DTO, pero el controller aún puede estar pasando `request.getBalance()`, que ya no existe tras Task 1 — si Task 1 se completó correctamente este test en particular ya debería compilar y pasar solo el primero; `getLedgerReturnsEntriesForOwner` sí falla porque el endpoint no existe).

- [ ] **Step 3: Crear `BalanceLedgerEntryResponse`**

`account-service/src/main/java/com/loyalty/account/dto/BalanceLedgerEntryResponse.java`:
```java
package com.loyalty.account.dto;

import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;

import java.time.Instant;

public class BalanceLedgerEntryResponse {

    private String id;
    private String accountId;
    private BalanceLedgerEventType eventType;
    private String transactionId;
    private long delta;
    private long balanceBefore;
    private long balanceAfter;
    private Instant createdAt;

    public static BalanceLedgerEntryResponse from(BalanceLedgerEntry entry) {
        BalanceLedgerEntryResponse response = new BalanceLedgerEntryResponse();
        response.id = entry.getId();
        response.accountId = entry.getAccountId();
        response.eventType = entry.getEventType();
        response.transactionId = entry.getTransactionId();
        response.delta = entry.getDelta();
        response.balanceBefore = entry.getBalanceBefore();
        response.balanceAfter = entry.getBalanceAfter();
        response.createdAt = entry.getCreatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BalanceLedgerEventType getEventType() {
        return eventType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceBefore() {
        return balanceBefore;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 4: Agregar el endpoint al controller**

Modificar `account-service/src/main/java/com/loyalty/account/controller/AccountController.java` — agregar el constructor con `BalanceLedgerRepository`, el import, y el método:
```java
    private final BalanceLedgerRepository ledgerRepository;

    public AccountController(AccountService accountService, TransferClient transferClient,
                              BalanceLedgerRepository ledgerRepository) {
        this.accountService = accountService;
        this.transferClient = transferClient;
        this.ledgerRepository = ledgerRepository;
    }
```
```java
    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<BalanceLedgerEntryResponse>> getLedger(@PathVariable("id") String id,
                                                                       Authentication authentication) {
        String requesterId = subjectOf(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        accountService.getByIdForRequester(id, requesterId, isAdmin);

        List<BalanceLedgerEntryResponse> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc(id).stream()
                .map(BalanceLedgerEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }
```

(agregar los imports `com.loyalty.account.dto.BalanceLedgerEntryResponse`, `com.loyalty.account.repository.BalanceLedgerRepository`, `java.util.List`).

- [ ] **Step 5: Ejecutar y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountControllerIT`
Expected: PASS (todos los tests existentes + el nuevo).

- [ ] **Step 6: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/dto/BalanceLedgerEntryResponse.java account-service/src/main/java/com/loyalty/account/controller/AccountController.java account-service/src/test/java/com/loyalty/account/controller/AccountControllerIT.java
git commit -m "feat: agrega endpoint GET /accounts/{id}/ledger"
```

---

### Task 6: Endpoint `PATCH /accounts/{id}/limits`

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/dto/UpdateLimitsRequest.java`
- Modify: `account-service/src/main/java/com/loyalty/account/service/AccountService.java`
- Modify: `account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java`
- Modify: `account-service/src/main/java/com/loyalty/account/controller/AccountController.java`
- Test: `account-service/src/test/java/com/loyalty/account/controller/AccountLimitsControllerIT.java`
- Test: `account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `Account` (Task 1), `AccountRepository` (Fase 1).
- Produces: `AccountService.updateMinBalance(String accountId, Long minBalance)`. `PATCH /accounts/{id}/limits` → `200` con la cuenta actualizada, solo `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`). No consumido por tareas posteriores — es la última tarea de este plan.

- [ ] **Step 1: Escribir el test de servicio que falla**

Agregar a `account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java`:
```java
    @Test
    void updateMinBalancePersistsNewLimit() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account updated = accountService.updateMinBalance("acc-1", -1000L);

        assertThat(updated.getMinBalance()).isEqualTo(-1000L);
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: FAIL — `updateMinBalance` no existe.

- [ ] **Step 3: Agregar el método a la interfaz e implementación**

`account-service/src/main/java/com/loyalty/account/service/AccountService.java` — agregar:
```java
    Account updateMinBalance(String accountId, Long minBalance);
```

`account-service/src/main/java/com/loyalty/account/service/AccountServiceImpl.java` — agregar:
```java
    @Override
    public Account updateMinBalance(String accountId, Long minBalance) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        account.setMinBalance(minBalance);
        account.setUpdatedAt(Instant.now());
        return accountRepository.save(account);
    }
```

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountServiceTest`
Expected: PASS.

- [ ] **Step 5: Crear `UpdateLimitsRequest`**

`account-service/src/main/java/com/loyalty/account/dto/UpdateLimitsRequest.java`:
```java
package com.loyalty.account.dto;

public class UpdateLimitsRequest {

    private Long minBalance;

    public Long getMinBalance() {
        return minBalance;
    }

    public void setMinBalance(Long minBalance) {
        this.minBalance = minBalance;
    }
}
```

- [ ] **Step 6: Escribir el test de integración del endpoint**

`account-service/src/test/java/com/loyalty/account/controller/AccountLimitsControllerIT.java`:
```java
package com.loyalty.account.controller;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccountLimitsControllerIT {

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

    @BeforeEach
    void setUp() {
        Account account = new Account();
        account.setId("acc-limits-test");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setMinBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
    }

    @Test
    void nonAdminCannotUpdateLimits() throws Exception {
        mockMvc.perform(patch("/accounts/acc-limits-test/limits")
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content("{\"minBalance\": -500}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUpdateLimits() throws Exception {
        mockMvc.perform(patch("/accounts/acc-limits-test/limits")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"minBalance\": -500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minBalance").value(-500));
    }
}
```

- [ ] **Step 7: Ejecutar y confirmar que falla**

Run: `mvn -q -pl account-service -am test -Dtest=AccountLimitsControllerIT`
Expected: FAIL — el endpoint no existe (`404`).

- [ ] **Step 8: Agregar el endpoint al controller**

Modificar `account-service/src/main/java/com/loyalty/account/controller/AccountController.java` — agregar:
```java
    @PatchMapping("/{id}/limits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> updateLimits(@PathVariable("id") String id,
                                                         @RequestBody UpdateLimitsRequest request) {
        Account updated = accountService.updateMinBalance(id, request.getMinBalance());
        return ResponseEntity.ok(AccountResponse.from(updated));
    }
```
(agregar el import `com.loyalty.account.dto.UpdateLimitsRequest`).

- [ ] **Step 9: Ejecutar y confirmar que pasa**

Run: `mvn -q -pl account-service -am test -Dtest=AccountLimitsControllerIT`
Expected: PASS (2 tests).

- [ ] **Step 10: Ejecutar toda la suite de `account-service` para confirmar que no queda nada roto**

Run: `mvn -q -pl account-service -am test`
Expected: `BUILD SUCCESS`, todos los tests en verde (Fase 1 + los agregados en este plan).

- [ ] **Step 11: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/dto/UpdateLimitsRequest.java account-service/src/main/java/com/loyalty/account/service/ account-service/src/main/java/com/loyalty/account/controller/AccountController.java account-service/src/test/java/com/loyalty/account/controller/AccountLimitsControllerIT.java account-service/src/test/java/com/loyalty/account/service/AccountServiceTest.java
git commit -m "feat: agrega endpoint PATCH /accounts/{id}/limits (solo ADMIN)"
```

---

## Self-Review

**1. Spec coverage:**
- `minBalance` en `Account`, default `0`, nullable → Task 1, Task 3.
- `POST /accounts` sin `balance` → Task 1.
- `balance_ledger` (D6: accountId, eventType, transactionId, delta, balanceBefore, balanceAfter, createdAt) → Task 2.
- Transacción multi-documento junto al `$inc` (D4) → Task 3.
- Sin campos duplicados entre colecciones (D5): `balance_ledger` solo referencia `transactionId`, no copia `amount`/`status` de `Transaction` → Task 2, Task 3 (el modelo nunca incluye esos campos).
- `acc-treasury` sembrada al arranque, `minBalance: null` (D1) → Task 4.
- `PATCH /accounts/{id}/limits`, solo `ADMIN` → Task 6.
- `GET /accounts/{id}/ledger` → Task 5.
- Distinción `SEED` vs `CREDIT` (primera línea de crédito de una cuenta) → Task 3, verificado explícitamente en `AccountAtomicOperationsIT`.
- Tests de Fase 1 que rompen (`AccountServiceTest`, `AccountAtomicOperationsIT`, `AccountControllerIT`) → actualizados en Tasks 1, 3, 5 respectivamente. `SagaEventListenerIT` verificado en Task 3 Step 9 sin necesitar cambios propios (solo dependía de `SagaEventListener`, ya actualizado).

**2. Placeholder scan:** sin TBD/TODO. Todo el código de cada step es literal y ejecutable.

**3. Type consistency:** `AccountAtomicOperations` cambia de 3 métodos de 2 parámetros a 3 métodos de 3 parámetros (`transactionId` agregado) de forma consistente entre la interfaz (Task 3 Step 4), la implementación (Step 5), el único consumidor (`SagaEventListener`, Step 7) y el test (Step 1). `BalanceLedgerEntry`/`BalanceLedgerEventType` (Task 2) se usan idénticos en Task 3 (escritura) y Task 5 (lectura vía `BalanceLedgerEntryResponse`). `Account.minBalance` (Task 1) se lee consistentemente en Task 3 (vía `$ifNull` con default `0`) y se escribe en Task 4 (seed, `null`) y Task 6 (`PATCH .../limits`).

## Nota sobre el gap de contrato cruzado con `transfer-service`

`transfer-service` (Fase 2) llama a `account-service` vía `AccountClient.fetchAccount(...)` y espera un `AccountView` con campos `id/ownerId/balance/status` — este plan no le agrega `minBalance` a esa vista porque `transfer-service` no necesita saberlo: su única validación relevante es `status == ACTIVE`, el chequeo de `minBalance` real ocurre atómicamente dentro de `account-service` al procesar el evento `DebitRequested` (igual que hoy ocurre con `INSUFFICIENT_BALANCE`, un chequeo que también puede fallar de forma asíncrona pese a que la validación síncrona previa de `transfer-service` lo haya dejado pasar). No se requiere ningún cambio en `transfer-service` para este plan.
