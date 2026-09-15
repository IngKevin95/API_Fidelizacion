package com.loyalty.transfer.exception;

public class TransferUnprocessableException extends RuntimeException {

    private final String code;

    public TransferUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
