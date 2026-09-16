package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class AccountLimitsE2ETest {

    @Test
    void adminUpdatesMinBalanceReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("limits-ok");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"minBalance\": 50}")
                .patch("/accounts/" + accountId + "/limits")
                .then().statusCode(200)
                .body("minBalance", equalTo(50));
    }

    @Test
    void adminSetsNegativeMinBalanceOnTreasuryReturns200() {
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"minBalance\": -1000000}")
                .patch("/accounts/acc-treasury/limits")
                .then().statusCode(200)
                .body("minBalance", equalTo(-1000000));
    }

    @Test
    void nonAdminUpdatingLimitsReturns403() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("limits-nonadmin");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{\"minBalance\": 0}")
                .patch("/accounts/" + accountId + "/limits")
                .then().statusCode(403);
    }

    @Test
    void missingMinBalanceFieldReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("limits-missing");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{}")
                .patch("/accounts/" + accountId + "/limits")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void updateLimitsOfNonExistentAccountReturns404() {
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"minBalance\": 0}")
                .patch("/accounts/acc-no-existe-12345/limits")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
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
