package com.loyalty.transfer.saga.events;

public record DebitRequestedEvent(String transactionId, String sourceAccountId, long amount, String timestamp) {
}
