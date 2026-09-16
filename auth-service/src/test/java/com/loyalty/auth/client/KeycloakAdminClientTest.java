package com.loyalty.auth.client;

import com.loyalty.auth.exception.UserAlreadyExistsException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakAdminClientTest {

    private MockWebServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new KeycloakAdminClient(restClient, "loyalty-realm", "loyalty-app", "loyalty-app-dev-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void createUserSucceedsWithFullFlow() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"svc-token\",\"expires_in\":300}"));
        server.enqueue(new MockResponse().setResponseCode(201));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"user-uuid-1\",\"username\":\"newuser\"}]"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"role-uuid\",\"name\":\"USER\"}"));
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThatCode(() -> client.createUser("newuser", "newuser@loyalty.local", "Password123!"))
                .doesNotThrowAnyException();
    }

    @Test
    void createUserThrowsUserAlreadyExistsOn409() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"svc-token\",\"expires_in\":300}"));
        server.enqueue(new MockResponse().setResponseCode(409));

        assertThatThrownBy(() -> client.createUser("dup", "dup@loyalty.local", "Password123!"))
                .isInstanceOf(UserAlreadyExistsException.class);
    }
}