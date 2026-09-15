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