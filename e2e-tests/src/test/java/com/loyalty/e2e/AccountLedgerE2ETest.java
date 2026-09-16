package com.loyalty.e2e;

import com.loyalty.e2e.support.AdminTransferFixture;
import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

class AccountLedgerE2ETest {

    @Test
    void ownerViewsOwnLedgerReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("ledger-owner");
        String accountId = createAccount(user.accessToken());
        AdminTransferFixture.fundAccount(accountId, 10);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                ApiClient.gateway()
                        .header("Authorization", "Bearer " + user.accessToken())
                        .get("/accounts/" + accountId + "/ledger")
                        .then().statusCode(200)
                        .body("size()", greaterThanOrEqualTo(1))
                        .body("[0].eventType", equalTo("SEED")));
    }

    @Test
    void adminViewsTreasuryLedgerReturns200() {
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .get("/accounts/acc-treasury/ledger")
                .then().statusCode(200);
    }

    @Test
    void nonOwnerViewingLedgerReturns403() {
        AuthTestFixture.RegisteredUser owner = AuthTestFixture.registerAndLogin("ledger-owner2");
        String accountId = createAccount(owner.accessToken());
        AuthTestFixture.RegisteredUser intruder = AuthTestFixture.registerAndLogin("ledger-intruder");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + intruder.accessToken())
                .get("/accounts/" + accountId + "/ledger")
                .then().statusCode(403);
    }

    @Test
    void viewLedgerOfNonExistentAccountReturns404() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("ledger-404");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .get("/accounts/acc-no-existe-12345/ledger")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void viewLedgerWithoutTokenReturns401() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("ledger-notoken");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .get("/accounts/" + accountId + "/ledger")
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
