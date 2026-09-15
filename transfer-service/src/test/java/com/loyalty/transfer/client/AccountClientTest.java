package com.loyalty.transfer.client;

import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.AccountNotFoundException;
import com.loyalty.transfer.exception.TransferAccessDeniedException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountClientTest {

    private MockWebServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        accountClient = new AccountClient(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchAccountReturnsAccountViewOn200() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"acc-1\",\"ownerId\":\"user-1\",\"balance\":100,\"status\":\"ACTIVE\"}"));

        AccountView account = accountClient.fetchAccount("acc-1", "token-123");

        assertThat(account.getId()).isEqualTo("acc-1");
        assertThat(account.getBalance()).isEqualTo(100L);
    }

    @Test
    void fetchAccountThrowsNotFoundOn404() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> accountClient.fetchAccount("acc-missing", "token-123"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void fetchAccountThrowsAccessDeniedOn403() {
        server.enqueue(new MockResponse().setResponseCode(403));

        assertThatThrownBy(() -> accountClient.fetchAccount("acc-1", "token-123"))
                .isInstanceOf(TransferAccessDeniedException.class);
    }
}