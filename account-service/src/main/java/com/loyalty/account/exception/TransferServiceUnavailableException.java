package com.loyalty.account.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class TransferServiceUnavailableException extends RuntimeException {
    public TransferServiceUnavailableException(String message) {
        super(message);
    }
}
