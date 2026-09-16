package com.loyalty.auth.exception;

public class KeycloakUnavailableException extends RuntimeException {
    public KeycloakUnavailableException(String message) {
        super(message);
    }
}
