package com.loyalty.account.saga.events;

public record DebitResultEvent(String transactionId, boolean success, String reason) {

    public static DebitResultEvent succeeded(String transactionId) {
        return new DebitResultEvent(transactionId, true, null);
    }

    public static DebitResultEvent failed(String transactionId, String reason) {
        return new DebitResultEvent(transactionId, false, reason);
    }
}