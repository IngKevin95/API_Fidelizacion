package com.loyalty.transfer.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void mapsRealmRolesToSpringAuthorities() {
        SecurityConfig config = new SecurityConfig();

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .claim("realm_access", Map.of("roles", List.of("USER", "ADMIN")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        JwtAuthenticationToken token = (JwtAuthenticationToken) config.jwtAuthenticationConverter().convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
}