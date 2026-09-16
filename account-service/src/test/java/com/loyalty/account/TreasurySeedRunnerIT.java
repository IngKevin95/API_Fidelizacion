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
