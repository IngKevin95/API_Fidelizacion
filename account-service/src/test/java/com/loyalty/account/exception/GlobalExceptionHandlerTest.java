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

    @Test
    void mapsMalformedBodyTo400NotInternalError() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<Map<String, Object>> response = handler.handleMalformedBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void handlesUnexpectedExceptionWithGenericFormat() {
        RuntimeException ex = new RuntimeException("detalle interno sensible");

        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody()).containsEntry("message", "Ocurrio un error inesperado");
        assertThat(response.getBody()).containsKey("timestamp");
    }
}
