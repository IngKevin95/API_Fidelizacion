package com.loyalty.account.client;

import com.loyalty.account.dto.TransactionView;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferClientTest {

    private MockWebServer server;
    private TransferClient transferClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        org.springframework.boot.web.client.ClientHttpRequestFactorySettings settings = org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(java.time.Duration.ofSeconds(3))
                .withReadTimeout(java.time.Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(settings))
                .build();
        transferClient = new TransferClient(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void listTransactionsReturnsDeserializedList() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"tx-1\",\"sourceAccountId\":\"acc-1\",\"targetAccountId\":\"acc-2\",\"amount\":40,\"status\":\"COMPLETED\"}]"));

        List<TransactionView> result = transferClient.listTransactions("acc-1", "token-123");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("tx-1");
        assertThat(result.get(0).getAmount()).isEqualTo(40L);
    }

    @Test
    void listTransactionsThrowsExceptionOnTimeout() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
                .setBodyDelay(6, java.util.concurrent.TimeUnit.SECONDS));

        assertThatThrownBy(() -> transferClient.listTransactions("acc-1", "token-123"))
                .isInstanceOf(Exception.class);
    }
}
