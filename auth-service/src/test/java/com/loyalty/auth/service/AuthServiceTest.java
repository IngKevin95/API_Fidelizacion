package com.loyalty.auth.service;

import com.loyalty.auth.client.KeycloakAdminClient;
import com.loyalty.auth.client.KeycloakTokenClient;
import com.loyalty.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private KeycloakAdminClient adminClient;

    @Mock
    private KeycloakTokenClient tokenClient;

    @Test
    void registerDelegatesToAdminClient() {
        AuthService service = new AuthServiceImpl(adminClient, tokenClient);

        service.register("newuser", "newuser@loyalty.local", "Password123!");

        verify(adminClient).createUser("newuser", "newuser@loyalty.local", "Password123!");
    }

    @Test
    void loginDelegatesToTokenClient() {
        AuthService service = new AuthServiceImpl(adminClient, tokenClient);
        TokenResponse expected = new TokenResponse("token", "refresh", 300L);
        when(tokenClient.login("test-user", "TestUser123!")).thenReturn(expected);

        TokenResponse result = service.login("test-user", "TestUser123!");

        assertThat(result).isEqualTo(expected);
    }
}
