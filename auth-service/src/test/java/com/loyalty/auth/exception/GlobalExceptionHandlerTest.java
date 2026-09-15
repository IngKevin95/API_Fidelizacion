package com.loyalty.auth.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsUserAlreadyExistsTo409() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUserExists(new UserAlreadyExistsException("ya existe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "USER_ALREADY_EXISTS");
    }

    @Test
    void mapsInvalidCredentialsTo401() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleInvalidCredentials(new InvalidCredentialsException("credenciales invalidas"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void mapsKeycloakUnavailableTo503() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleKeycloakUnavailable(new KeycloakUnavailableException("keycloak no responde"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "KEYCLOAK_UNAVAILABLE");
    }
}
