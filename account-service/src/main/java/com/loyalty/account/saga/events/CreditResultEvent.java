package com.loyalty.account.saga.events;

public record CreditResultEvent(String transactionId, boolean success, String reason) {

    public static CreditResultEvent succeeded(String transactionId) {
        return new CreditResultEvent(transactionId, true, null);
    }

    public static CreditResultEvent failed(String transactionId, String reason) {
        return new CreditResultEvent(transactionId, false, reason);
    }
}