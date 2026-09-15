package com.loyalty.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "eureka.client.enabled=false")
class RoutingIT {

    private static DisposableServer authServiceMock;
    private static DisposableServer accountServiceMock;
    private static DisposableServer transferServiceMock;

    private static final AtomicReference<String> lastAccountsAuthHeader = new AtomicReference<>();

    @BeforeAll
    static void startMocks() {
        authServiceMock = HttpServer.create()
                .port(18191)
                .route(routes -> routes.post("/auth/register",
                        (req, res) -> res.status(201).send()))
                .bindNow();

        accountServiceMock = HttpServer.create()
                .port(18192)
                .route(routes -> routes.get("/accounts/acc-1", (req, res) -> {
                    lastAccountsAuthHeader.set(req.requestHeaders().get("Authorization"));
                    return res.status(200).sendString(Mono.just("{}"));
                }))
                .bindNow();

        transferServiceMock = HttpServer.create()
                .port(18193)
                .route(routes -> routes.post("/api/v1/points/transfer",
                        (req, res) -> res.status(202).send()))
                .bindNow();
    }

    @AfterAll
    static void stopMocks() {
        authServiceMock.disposeNow();
        accountServiceMock.disposeNow();
        transferServiceMock.disposeNow();
    }

    @AfterEach
    void resetState() {
        lastAccountsAuthHeader.set(null);
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void routesAuthRequestsToAuthService() {
        webTestClient().post().uri("/auth/register")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void routesAccountRequestsToAccountServiceAndForwardsAuthorizationHeader() {
        webTestClient().get().uri("/accounts/acc-1")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .expectStatus().isOk();

        assertThat(lastAccountsAuthHeader.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void routesTransferRequestsToTransferService() {
        webTestClient().post().uri("/api/v1/points/transfer")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }
}
