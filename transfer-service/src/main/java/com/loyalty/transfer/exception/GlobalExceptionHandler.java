package com.loyalty.transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SameAccountException.class)
    public ResponseEntity<Map<String, Object>> handleSameAccount(SameAccountException ex) {
        return body(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Payload invalido");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TransferAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(TransferAccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage());
    }

    @ExceptionHandler(TransferUnprocessableException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(TransferUnprocessableException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(payload);
    }
}
