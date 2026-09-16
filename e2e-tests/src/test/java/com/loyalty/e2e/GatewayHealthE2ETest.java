package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

class GatewayHealthE2ETest {

    @Test
    void gatewayHealthReturns200Up() {
        ApiClient.gateway()
                .get("/actuator/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }
}
