package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class AccountStatusE2ETest {

    @Test
    void adminDeactivatesAccountReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("status-deactivate");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"status\":\"INACTIVE\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(200)
                .body("status", equalTo("INACTIVE"));
    }

    @Test
    void adminReactivatesAccountReturns200() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("status-reactivate");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway().header("Authorization", adminToken)
                .body("{\"status\":\"INACTIVE\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(200);

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"status\":\"ACTIVE\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void nonAdminUpdatingStatusReturns403() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("status-nonadmin");
        String accountId = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{\"status\":\"INACTIVE\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(403);
    }

    @Test
    void invalidStatusValueReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("status-invalidvalue");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"status\":\"SUSPENDIDA\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(400);
    }

    @Test
    void missingStatusFieldReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("status-missing");
        String accountId = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void updateStatusOfNonExistentAccountReturns404() {
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"status\":\"INACTIVE\"}")
                .patch("/accounts/acc-no-existe-12345/status")
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
