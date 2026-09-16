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
@EmbeddedKafka(partitions = 1, topics = {"debit-events", "credit-events", "transfer-compensation", "debit-results", "credit-results", "compensation-results"})
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

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.loyalty.transfer.client.AccountClient accountClient;

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
    void creditFailedTriggersCompensationAndMarksCompensating() {
        savePending("tx-4", "acc-1", "acc-2", 40L);

        kafkaTemplate.send("credit-results", "tx-4", new CreditResultEvent("tx-4", false, "TARGET_INACTIVE"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction tx = transactionRepository.findById("tx-4").orElseThrow();
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPENSATING);
            assertThat(tx.getFailureReason()).isEqualTo("TARGET_INACTIVE");
            assertThat(tx.getCompletedAt()).isNull();
        });
    }

    @Test
    void compensationResultMarksTransactionFailed() {
        Transaction tx = new Transaction();
        tx.setId("tx-5");
        tx.setSourceAccountId("acc-1");
        tx.setTargetAccountId("acc-2");
        tx.setAmount(40L);
        tx.setStatus(TransactionStatus.COMPENSATING);
        tx.setCreatedAt(Instant.now());
        transactionRepository.save(tx);

        kafkaTemplate.send("compensation-results", "tx-5", new com.loyalty.transfer.saga.events.CompensationResultEvent("tx-5", true));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Transaction updatedTx = transactionRepository.findById("tx-5").orElseThrow();
            assertThat(updatedTx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(updatedTx.getCompletedAt()).isNotNull();
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
