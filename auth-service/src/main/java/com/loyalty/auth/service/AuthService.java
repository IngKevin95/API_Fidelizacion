package com.loyalty.auth.service;

import com.loyalty.auth.dto.TokenResponse;

public interface AuthService {

    void register(String username, String email, String password);

    TokenResponse login(String username, String password);
}
