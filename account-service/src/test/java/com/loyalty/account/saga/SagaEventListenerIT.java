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
import org.springframework.context.annotation.Import;
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
@EmbeddedKafka(partitions = 1, topics = {"debit-events", "credit-events", "transfer-compensation", "debit-results", "credit-results"})
@Import(TestResultCollector.class)
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
                new CreditRequestedEvent("tx-3", "acc-treasury", "acc-3", 25L, Instant.now().toString()));

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