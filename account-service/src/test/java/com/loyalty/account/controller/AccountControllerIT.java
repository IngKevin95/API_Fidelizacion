package com.loyalty.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class AccountControllerIT {

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

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        accountRepository.deleteAll();
    }

    @Test
    void createAccountReturns201WithOwnerFromJwtAndZeroBalance() throws Exception {
        mockMvc.perform(post("/accounts")
                        .with(jwt().jwt(j -> j.subject("user-1")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("user-1"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getAccountReturns403WhenNotOwnerAndNotAdmin() throws Exception {
        Account account = new Account();
        account.setId("acc-other");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        mockMvc.perform(get("/accounts/acc-other")
                        .with(jwt().jwt(j -> j.subject("intruder"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateStatusRequiresAdminRole() throws Exception {
        Account account = new Account();
        account.setId("acc-status");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        mockMvc.perform(patch("/accounts/acc-status/status")
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content("{\"status\": \"INACTIVE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/accounts/acc-status/status")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"status\": \"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

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
}
