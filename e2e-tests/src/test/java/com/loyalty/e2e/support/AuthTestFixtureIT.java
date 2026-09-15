package com.loyalty.e2e.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTestFixtureIT {

    @Test
    void registerAndLoginReturnsValidAccessToken() {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("fixture-smoke");

        assertThat(user.accessToken()).isNotBlank();
    }
}
