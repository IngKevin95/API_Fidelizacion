package com.loyalty.account.saga.events;

public record CompensateDebitEvent(String transactionId, String sourceAccountId, long amount) {
}