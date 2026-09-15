package com.loyalty.auth.service;

import com.loyalty.auth.client.KeycloakAdminClient;
import com.loyalty.auth.client.KeycloakTokenClient;
import com.loyalty.auth.dto.TokenResponse;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final KeycloakAdminClient adminClient;
    private final KeycloakTokenClient tokenClient;

    public AuthServiceImpl(KeycloakAdminClient adminClient, KeycloakTokenClient tokenClient) {
        this.adminClient = adminClient;
        this.tokenClient = tokenClient;
    }

    @Override
    public void register(String username, String email, String password) {
        adminClient.createUser(username, email, password);
    }

    @Override
    public TokenResponse login(String username, String password) {
        return tokenClient.login(username, password);
    }
}
