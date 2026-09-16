package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class AccountCreateE2ETest {

    @Test
    void createAccountReturns201WithZeroBalance() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-create");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{}")
                .post("/accounts")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("balance", equalTo(0))
                .body("minBalance", equalTo(0))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void createAccountIgnoresUnknownBalanceField() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-create-ignore");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{\"balance\": 999999}")
                .post("/accounts")
                .then().statusCode(201)
                .body("balance", equalTo(0));
    }

    @Test
    void createAccountWithoutTokenReturns401() {
        ApiClient.gateway()
                .body("{}")
                .post("/accounts")
                .then().statusCode(401);
    }

    @Test
    void createAccountWithInvalidTokenReturns401() {
        ApiClient.gateway()
                .header("Authorization", "Bearer token-invalido-no-jwt")
                .body("{}")
                .post("/accounts")
                .then().statusCode(401);
    }

    @Test
    void createAccountAsAdminOnlyReturns403() {
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{}")
                .post("/accounts")
                .then().statusCode(403);
    }
}
