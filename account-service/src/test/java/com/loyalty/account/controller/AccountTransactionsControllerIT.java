package com.loyalty.account.controller;

import com.loyalty.account.client.TransferClient;
import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.dto.TransactionView;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class AccountTransactionsControllerIT {

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

    @MockBean
    private TransferClient transferClient;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
    }

    @Test
    void ownerCanListTransactionsDelegatedToTransferService() throws Exception {
        Account account = new Account();
        account.setId("acc-tx-1");
        account.setOwnerId("user-1");
        account.setBalance(100L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        TransactionView tx = new TransactionView();
        tx.setId("tx-1");
        tx.setSourceAccountId("acc-tx-1");
        tx.setAmount(40L);
        when(transferClient.listTransactions(org.mockito.ArgumentMatchers.eq("acc-tx-1"), anyString()))
                .thenReturn(List.of(tx));

        mockMvc.perform(get("/accounts/acc-tx-1/transactions")
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tx-1"));
    }

    @Test
    void nonOwnerNonAdminGets403WithoutCallingTransferService() throws Exception {
        Account account = new Account();
        account.setId("acc-tx-2");
        account.setOwnerId("owner-1");
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        mockMvc.perform(get("/accounts/acc-tx-2/transactions")
                        .with(jwt().jwt(j -> j.subject("intruder"))))
                .andExpect(status().isForbidden());
    }
}
