package com.loyalty.transfer.saga.events;

public record CompensateDebitEvent(String transactionId, String sourceAccountId, long amount) {
}
