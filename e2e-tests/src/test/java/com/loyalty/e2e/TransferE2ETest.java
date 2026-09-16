package com.loyalty.e2e;

import com.loyalty.e2e.support.AdminTransferFixture;
import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class TransferE2ETest {

    @Test
    void transferBetweenOwnAccountsReturns202() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-ok");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());
        AdminTransferFixture.fundAccount(source, 100);

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, 40))
                .post("/api/v1/points/transfer")
                .then().statusCode(202)
                .body("status", equalTo("PENDING"));
    }

    @Test
    void transferSameAccountReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-same");
        String account = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(account, account, 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(400)
                .body("code", equalTo("SAME_ACCOUNT"));
    }

    @Test
    void transferWithInsufficientBalanceReturns422() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-insufficient");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, 999999999L))
                .post("/api/v1/points/transfer")
                .then().statusCode(422)
                .body("code", equalTo("INSUFFICIENT_BALANCE"));
    }

    @Test
    void transferFromInactiveSourceReturns422() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-sourceinactive");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());
        deactivate(source);

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(422)
                .body("code", equalTo("SOURCE_INACTIVE"));
    }

    @Test
    void transferToInactiveTargetReturns422() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-targetinactive");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());
        deactivate(target);

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(422)
                .body("code", equalTo("TARGET_INACTIVE"));
    }

    @Test
    void transferFromNonExistentSourceReturns404() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-sourcenotfound");
        String target = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody("acc-no-existe-12345", target, 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transferToNonExistentTargetReturns404() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-targetnotfound");
        String source = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, "acc-no-existe-12345", 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(404)
                .body("code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transferNegativeAmountReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-negative");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, -10))
                .post("/api/v1/points/transfer")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void transferZeroAmountReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-zero");
        String source = createAccount(user.accessToken());
        String target = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body(transferBody(source, target, 0))
                .post("/api/v1/points/transfer")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void transferBlankSourceAccountIdReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-blanksource");
        String target = createAccount(user.accessToken());

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{\"sourceAccountId\":\"\",\"targetAccountId\":\"" + target + "\",\"amount\":10}")
                .post("/api/v1/points/transfer")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void transferEmptyBodyReturns400() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-emptybody");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + user.accessToken())
                .body("{}")
                .post("/api/v1/points/transfer")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void transferWithoutTokenReturns401() {
        ApiClient.gateway()
                .body(transferBody("acc-1", "acc-2", 10))
                .post("/api/v1/points/transfer")
                .then().statusCode(401);
    }

    @Test
    void adminFundsAccountFromTreasuryReturns202() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("transfer-treasury");
        String target = createAccount(user.accessToken());
        String adminToken = AuthTestFixture.loginAsTestAdmin();

        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body(transferBody("acc-treasury", target, 50))
                .post("/api/v1/points/transfer")
                .then().statusCode(202)
                .body("status", equalTo("PENDING"));
    }

    private String createAccount(String accessToken) {
        return ApiClient.gateway()
                .header("Authorization", "Bearer " + accessToken)
                .body("{}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void deactivate(String accountId) {
        String adminToken = AuthTestFixture.loginAsTestAdmin();
        ApiClient.gateway()
                .header("Authorization", adminToken)
                .body("{\"status\":\"INACTIVE\"}")
                .patch("/accounts/" + accountId + "/status")
                .then().statusCode(200);
    }

    private String transferBody(String source, String target, long amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"targetAccountId\":\"" + target + "\",\"amount\":" + amount + "}";
    }
}
