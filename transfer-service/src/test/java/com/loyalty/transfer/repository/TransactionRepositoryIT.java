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