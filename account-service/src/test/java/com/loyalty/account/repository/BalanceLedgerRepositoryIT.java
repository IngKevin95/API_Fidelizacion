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
