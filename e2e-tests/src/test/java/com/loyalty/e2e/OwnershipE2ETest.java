package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

class OwnershipE2ETest {

    @Test
    void userCannotTransferFromAnotherUsersAccount() {
        AuthTestFixture.RegisteredUser userA = AuthTestFixture.registerAndLogin("owner-a");
        AuthTestFixture.RegisteredUser userB = AuthTestFixture.registerAndLogin("owner-b");

        String accountOfA = ApiClient.gateway()
                .header("Authorization", "Bearer " + userA.accessToken())
                .body("{\"balance\": 100}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        String accountOfB = ApiClient.gateway()
                .header("Authorization", "Bearer " + userB.accessToken())
                .body("{\"balance\": 0}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        ApiClient.gateway()
                .header("Authorization", "Bearer " + userB.accessToken())
                .body("{\"sourceAccountId\":\"" + accountOfA + "\",\"targetAccountId\":\"" + accountOfB + "\",\"amount\":10}")
                .post("/api/v1/points/transfer")
                .then().statusCode(403);
    }
}
