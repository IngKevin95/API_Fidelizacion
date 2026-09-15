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