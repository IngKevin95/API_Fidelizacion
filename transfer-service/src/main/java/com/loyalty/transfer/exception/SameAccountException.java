package com.loyalty.transfer.exception;

public class SameAccountException extends RuntimeException {
    public SameAccountException(String message) {
        super(message);
    }
}
