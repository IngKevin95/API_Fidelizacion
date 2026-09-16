package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class AuthLoginE2ETest {

    @Test
    void loginReturns200WithAccessToken() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("login-ok");

        ApiClient.gateway()
                .body("{\"username\":\"" + user.username() + "\",\"password\":\"" + user.password() + "\"}")
                .post("/auth/login")
                .then().statusCode(200)
                .body("accessToken", notNullValue());
    }

    @Test
    void loginAsTestAdminReturns200() {
        ApiClient.gateway()
                .body("{\"username\":\"test-admin\",\"password\":\"TestAdmin123!\"}")
                .post("/auth/login")
                .then().statusCode(200)
                .body("accessToken", notNullValue());
    }

    @Test
    void loginWrongPasswordReturns401() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("login-wrongpass");

        ApiClient.gateway()
                .body("{\"username\":\"" + user.username() + "\",\"password\":\"PasswordIncorrecta1!\"}")
                .post("/auth/login")
                .then().statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));
    }

    @Test
    void loginUnknownUserReturns401() {
        ApiClient.gateway()
                .body("{\"username\":\"no-existe-" + UUID.randomUUID() + "\",\"password\":\"Test1234!\"}")
                .post("/auth/login")
                .then().statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));
    }

    @Test
    void loginBlankUsernameReturns400() {
        ApiClient.gateway()
                .body("{\"username\":\"\",\"password\":\"Test1234!\"}")
                .post("/auth/login")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void loginEmptyBodyReturns400() {
        ApiClient.gateway()
                .body("{}")
                .post("/auth/login")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"));
    }
}
