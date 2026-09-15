package com.loyalty.transfer.controller;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.dto.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
@EmbeddedKafka(partitions = 1, topics = {"debit-events"})
class TransferControllerIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "rs0", "--bind_ip_all");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountClient accountClient;

    @Test
    void transferWithNonPositiveAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/points/transfer")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"acc-1\",\"targetAccountId\":\"acc-2\",\"amount\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validTransferReturns202WithPendingStatus() throws Exception {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), anyString())).thenReturn(source);
        when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-2"), anyString())).thenReturn(target);

        mockMvc.perform(post("/api/v1/points/transfer")
                        .with(jwt().jwt(j -> j.subject("user-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"acc-1\",\"targetAccountId\":\"acc-2\",\"amount\":40}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private AccountView accountView(String id, String ownerId, long balance, String status) {
        AccountView view = new AccountView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setBalance(balance);
        view.setStatus(status);
        return view;
    }
}
