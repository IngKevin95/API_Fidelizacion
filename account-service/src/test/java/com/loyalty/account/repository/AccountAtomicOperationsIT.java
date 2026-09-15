package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;
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
import java.util.List;
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

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-1", 40L, "tx-1");

        assertThat(outcome.isSuccess()).isTrue();
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

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-2", 40L, "tx-2");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(accountRepository.findById("acc-2").orElseThrow().getBalance()).isEqualTo(10L);
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-2")).isEmpty();
    }

    @Test
    void debitSucceedsBelowZeroWhenMinBalanceIsNegative() {
        saveAccount("acc-treasury-test", 0L, -1000L, AccountStatus.ACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-treasury-test", 500L, "tx-3");

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(accountRepository.findById("acc-treasury-test").orElseThrow().getBalance()).isEqualTo(-500L);
    }

    @Test
    void debitFailsWhenAccountInactive() {
        saveAccount("acc-3", 100L, 0L, AccountStatus.INACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-3", 10L, "tx-4");

        assertThat(outcome.isSuccess()).isFalse();
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
                    if (accountRepository.debitIfSufficientBalance("acc-concurrent", 30L, "tx-concurrent-" + index).isSuccess()) {
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

    @Test
    void debitReturnsAccountNotFoundReasonWhenAccountDoesNotExist() {
        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-nope", 10L, "tx-11");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.reason()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void debitReturnsSourceInactiveReasonWhenAccountInactive() {
        saveAccount("acc-9", 100L, 0L, AccountStatus.INACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-9", 10L, "tx-12");

        assertThat(outcome.reason()).isEqualTo("SOURCE_INACTIVE");
    }

    @Test
    void debitReturnsInsufficientBalanceReasonWhenMinBalanceViolated() {
        saveAccount("acc-10", 10L, 0L, AccountStatus.ACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-10", 40L, "tx-13");

        assertThat(outcome.reason()).isEqualTo("INSUFFICIENT_BALANCE");
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
