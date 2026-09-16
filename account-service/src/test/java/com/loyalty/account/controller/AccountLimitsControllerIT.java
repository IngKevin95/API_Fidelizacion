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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
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

    @Test
    void rejectsRequestWithoutMinBalance() throws Exception {
        mockMvc.perform(patch("/accounts/acc-limits-test/limits")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
