package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class InsufficientBalanceE2ETest {

    @Test
    void transferWithInsufficientBalanceReturns422WithoutMovingFunds() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("insufficient");
        String token = "Bearer " + user.accessToken();

        String sourceAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"balance\": 10}")
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
                .then().statusCode(422)
                .body("code", equalTo("INSUFFICIENT_BALANCE"));

        ApiClient.gateway()
                .header("Authorization", token)
                .get("/accounts/" + sourceAccountId)
                .then().statusCode(200)
                .body("balance", equalTo(10));
    }
}
