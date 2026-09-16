package com.loyalty.auth.client;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakTokenClientTest {

    private MockWebServer server;
    private KeycloakTokenClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new KeycloakTokenClient(restClient, "loyalty-realm", "loyalty-app", "loyalty-app-dev-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void loginReturnsTokenResponseOn200() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"abc\",\"refresh_token\":\"def\",\"expires_in\":300}"));

        TokenResponse response = client.login("test-user", "TestUser123!");

        assertThat(response.getAccessToken()).isEqualTo("abc");
        assertThat(response.getRefreshToken()).isEqualTo("def");
        assertThat(response.getExpiresIn()).isEqualTo(300L);
    }

    @Test
    void loginThrowsInvalidCredentialsOn401() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client.login("test-user", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}