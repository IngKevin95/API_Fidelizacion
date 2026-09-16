package com.loyalty.e2e.support;

import org.awaitility.Awaitility;
import java.time.Duration;

public class AdminTransferFixture {
    
    public static void fundAccount(String targetAccountId, long amount) {
        String token = AuthTestFixture.loginAsTestAdmin();
        
        Number currentBalanceNum = ApiClient.gateway()
                .header("Authorization", token)
                .get("/accounts/" + targetAccountId)
                .then()
                .statusCode(200)
                .extract().path("balance");
                
        long currentBalance = currentBalanceNum != null ? currentBalanceNum.longValue() : 0L;
        long expectedBalance = currentBalance + amount;

        ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"sourceAccountId\":\"acc-treasury\",\"targetAccountId\":\"" + targetAccountId + "\",\"amount\":" + amount + "}")
                .post("/api/v1/points/transfer")
                .then()
                .statusCode(202);

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
            Number newBalance = ApiClient.gateway()
                    .header("Authorization", token)
                    .get("/accounts/" + targetAccountId)
                    .then()
                    .statusCode(200)
                    .extract().path("balance");
            return newBalance != null && newBalance.longValue() >= expectedBalance;
        });
    }
}