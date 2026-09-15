package com.loyalty.account.saga.events;

public record CreditRequestedEvent(String transactionId, String targetAccountId, long amount, String timestamp) {
}