package com.loyalty.e2e;

import com.loyalty.e2e.support.AdminTransferFixture;
import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

class HappyPathE2ETest {

    @Test
    void registerCreateAccountsTransferAndVerifyBalances() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("happy-path");
        String token = "Bearer " + user.accessToken();

        String sourceAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        String targetAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        AdminTransferFixture.fundAccount(sourceAccountId, 100);

        String transactionId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"sourceAccountId\":\"" + sourceAccountId + "\",\"targetAccountId\":\"" + targetAccountId + "\",\"amount\":40}")
                .post("/api/v1/points/transfer")
                .then().statusCode(202)
                .extract().path("transactionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                ApiClient.gateway()
                        .header("Authorization", token)
                        .get("/accounts/" + sourceAccountId)
                        .then().statusCode(200)
                        .body("balance", equalTo(60)));

        ApiClient.gateway()
                .header("Authorization", token)
                .get("/accounts/" + targetAccountId)
                .then().statusCode(200)
                .body("balance", equalTo(40));

        ApiClient.gateway()
                .header("Authorization", token)
                .get("/accounts/" + sourceAccountId + "/transactions")
                .then().statusCode(200)
                .body("find { it.id == '" + transactionId + "' }.status", equalTo("COMPLETED"));
    }
}
