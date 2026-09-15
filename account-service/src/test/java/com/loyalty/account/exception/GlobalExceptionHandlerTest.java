package com.loyalty.account.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAccountNotFoundTo404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccountNotFound(new AccountNotFoundException("no existe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "no existe");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void mapsAccountAccessDeniedTo403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccountAccessDeniedException("no autorizado"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "ACCESS_DENIED");
    }
}
