package com.loyalty.transfer.exception;

public class TransferAccessDeniedException extends RuntimeException {
    public TransferAccessDeniedException(String message) {
        super(message);
    }
}
