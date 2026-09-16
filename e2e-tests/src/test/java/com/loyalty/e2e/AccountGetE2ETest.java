package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class AccountGetE2ETest {

    @Test
    void ownerGetsOwnAccountReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-get-owner");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .get("/accounts/" + accountId)
                .then().statusCode(200)
                .body("id", equalTo(accountId));
    }

    @Test
    void adminGetsAnyAccountReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-get-admin");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .get("/accounts/" + accountId)
                .then().statusCode(200)
                .body("id", equalTo(accountId));
    }

    @Test
    void adminGetsTreasuryAccountReturns200() {
        // Nota: no se afirma un valor especifico de minBalance aqui - otra clase de esta
        // misma suite (AccountLimitsE2ETest) muta deliberadamente el minBalance real de
        // acc-treasury como parte de su propio escenario, y el orden entre clases de test
        // no esta garantizado. Solo se verifica que el endpoint responde 200 con el id correcto.
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .get("/accounts/acc-treasury")
                .then().statusCode(200)
                .body("id", equalTo("acc-treasury"));
    }

    @Test
    void nonOwnerGetsAccountReturns403() {
        AuthTestFixture.RegisteredUser owner = AuthTestFixture.registerAndLogin("acc-get-owner2");
        String accountId = createAccount(owner.accessToken());
        AuthTestFixture.RegisteredUser intruder = AuthTestFixture.registerAndLogin("acc-get-intruder");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + intruder.accessToken())
                .get("/accounts/" + accountId)
                .then().statusCode(403);
    }

    @Test
    void getNonExistentAccountReturns404() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-get-404");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .get("/accounts/acc-no-existe-12345")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getAccountWithoutTokenReturns401() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("acc-get-notoken");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .get("/accounts/" + accountId)
                .then().statusCode(401);
    }

    private String createAccount(String accessToken) {
        return ApiClient.gateway()
                .header("Authorization", "Bearer " + accessToken)
                .body("{}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");
    }
}
