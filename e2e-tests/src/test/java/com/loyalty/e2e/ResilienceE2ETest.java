package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

class ResilienceE2ETest {

    private static final String ACCOUNT_SERVICE_CONTAINER = "api_fidelizacion-account-service-1";

    @Test
    void transferResolvesAfterAccountServiceRestartsDuringSaga() throws Exception {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("resilience");
        String token = "Bearer " + user.accessToken();

        String sourceAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"balance\": 100}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        String targetAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"balance\": 0}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"sourceAccountId\":\"" + sourceAccountId + "\",\"targetAccountId\":\"" + targetAccountId + "\",\"amount\":40}")
                .post("/api/v1/points/transfer")
                .then().statusCode(202);

        // Detiene account-service inmediatamente despues de aceptar la transferencia,
        // sin esperar a que la saga termine de procesarse (carrera con el consumidor Kafka).
        // Si account-service ya proceso el evento antes de detenerse, la asercion final
        // igual se cumple (el resultado es el mismo); lo que se prueba es que el sistema
        // tolera el reinicio del contenedor sin perder el evento ni corromper el estado.
        runDockerCommand("stop", ACCOUNT_SERVICE_CONTAINER);
        try {
            Thread.sleep(1000);
        } finally {
            runDockerCommand("start", ACCOUNT_SERVICE_CONTAINER);
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                ApiClient.gateway()
                        .header("Authorization", token)
                        .get("/accounts/" + sourceAccountId)
                        .then().statusCode(200)
                        .body("balance", equalTo(60)));
    }

    private void runDockerCommand(String action, String containerName) throws Exception {
        Process process = new ProcessBuilder("docker", action, containerName)
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("docker " + action + " " + containerName + " fallo con exit code " + exitCode);
        }
    }
}
