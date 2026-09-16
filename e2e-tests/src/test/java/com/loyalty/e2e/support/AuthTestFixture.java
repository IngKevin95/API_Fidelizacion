package com.loyalty.e2e.support;

import java.util.UUID;

public final class AuthTestFixture {

    private AuthTestFixture() {
    }

    public record RegisteredUser(String username, String password, String accessToken) {
    }

    public static RegisteredUser registerAndLogin(String usernamePrefix) {
        String username = usernamePrefix + "-" + UUID.randomUUID();
        String password = "Test1234!";
        String email = username + "@loyalty.local";

        ApiClient.gateway()
                .body("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/auth/register")
                .then()
                .statusCode(201);

        String accessToken = ApiClient.gateway()
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract().path("accessToken");

        return new RegisteredUser(username, password, accessToken);
    }

    public static String loginAsTestAdmin() {
        String accessToken = ApiClient.gateway()
                .body("{\"username\":\"test-admin\",\"password\":\"TestAdmin123!\"}")
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract().path("accessToken");

        return "Bearer " + accessToken;
    }
}
