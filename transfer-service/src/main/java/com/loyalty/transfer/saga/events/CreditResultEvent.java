package com.loyalty.transfer.saga.events;

public record CreditResultEvent(String transactionId, boolean success, String reason) {
}
