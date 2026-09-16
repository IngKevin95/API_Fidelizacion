package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

class AuthRegisterE2ETest {

    @Test
    void registerReturns201() {
        String username = "reg-" + UUID.randomUUID();
        ApiClient.gateway()
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username + "@loyalty.local\",\"password\":\"Test1234!\"}")
                .post("/auth/register")
                .then().statusCode(201);
    }

    @Test
    void registerDuplicateUsernameReturns409() {
        String username = "dup-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username + "@loyalty.local\",\"password\":\"Test1234!\"}";

        ApiClient.gateway().body(body).post("/auth/register").then().statusCode(201);

        ApiClient.gateway().body(body).post("/auth/register")
                .then().statusCode(409)
                .body("code", equalTo("USER_ALREADY_EXISTS"));
    }

    @Test
    void registerBlankUsernameReturns400() {
        ApiClient.gateway()
                .body("{\"username\":\"\",\"email\":\"blank-user-" + UUID.randomUUID() + "@loyalty.local\",\"password\":\"Test1234!\"}")
                .post("/auth/register")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void registerInvalidEmailReturns400() {
        ApiClient.gateway()
                .body("{\"username\":\"invalid-email-" + UUID.randomUUID() + "\",\"email\":\"no-es-un-email\",\"password\":\"Test1234!\"}")
                .post("/auth/register")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void registerBlankEmailReturns400() {
        ApiClient.gateway()
                .body("{\"username\":\"blank-email-" + UUID.randomUUID() + "\",\"email\":\"\",\"password\":\"Test1234!\"}")
                .post("/auth/register")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void registerShortPasswordReturns400() {
        ApiClient.gateway()
                .body("{\"username\":\"short-pass-" + UUID.randomUUID() + "\",\"email\":\"short-pass-" + UUID.randomUUID() + "@loyalty.local\",\"password\":\"abc123\"}")
                .post("/auth/register")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void registerEmptyBodyReturns400() {
        ApiClient.gateway()
                .body("{}")
                .post("/auth/register")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }
}
