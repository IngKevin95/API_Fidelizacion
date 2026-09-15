package com.loyalty.transfer.saga.events;

public record DebitResultEvent(String transactionId, boolean success, String reason) {
}
