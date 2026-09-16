package com.loyalty.transfer.controller;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class TransactionQueryControllerIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.loyalty.transfer.client.AccountClient accountClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
    }

    @Test
    void rejectsWhenRequesterIsNotOwnerNorAdmin() throws Exception {
        saveTransaction("tx-1", "acc-1", "acc-2");
        org.mockito.Mockito.when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new com.loyalty.transfer.exception.TransferAccessDeniedException("No autorizado para acceder a la cuenta acc-1"));

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1")
                        .with(jwt().jwt(j -> j.subject("intruder")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsTransactionsWhereAccountIsSourceOrTarget() throws Exception {
        saveTransaction("tx-1", "acc-1", "acc-2");
        saveTransaction("tx-2", "acc-3", "acc-1");
        saveTransaction("tx-3", "acc-4", "acc-5");
        org.mockito.Mockito.when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.loyalty.transfer.dto.AccountView());

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1")
                        .with(jwt().jwt(j -> j.subject("user-1")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void saveTransaction(String id, String source, String target) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setSourceAccountId(source);
        tx.setTargetAccountId(target);
        tx.setAmount(10L);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setCreatedAt(Instant.now());
        transactionRepository.save(tx);
    }
}