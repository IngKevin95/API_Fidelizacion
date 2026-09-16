package com.loyalty.e2e;

import com.loyalty.e2e.support.AdminTransferFixture;
import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

class AccountTransactionsE2ETest {

    @Test
    void ownerListsOwnTransactionsReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("txns-owner");
        String accountId = createAccount(user.accessToken());
        AdminTransferFixture.fundAccount(accountId, 10);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                ApiClient.gateway()
                        .header("Authorization", "Bearer " + user.accessToken())
                        .get("/accounts/" + accountId + "/transactions")
                        .then().statusCode(200)
                        .body("size()", greaterThanOrEqualTo(1)));
    }

    @Test
    void adminListsAnyAccountTransactionsReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("txns-admin");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .get("/accounts/" + accountId + "/transactions")
                .then().statusCode(200);
    }

    @Test
    void nonOwnerListingTransactionsReturns403() {
        AuthTestFixture.RegisteredUser owner = AuthTestFixture.registerAndLogin("txns-owner2");
        String accountId = createAccount(owner.accessToken());
        AuthTestFixture.RegisteredUser intruder = AuthTestFixture.registerAndLogin("txns-intruder");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + intruder.accessToken())
                .get("/accounts/" + accountId + "/transactions")
                .then().statusCode(403);
    }

    @Test
    void listTransactionsOfNonExistentAccountReturns404() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("txns-404");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .get("/accounts/acc-no-existe-12345/transactions")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void listTransactionsWithoutTokenReturns401() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("txns-notoken");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .get("/accounts/" + accountId + "/transactions")
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
