package com.loyalty.transfer.saga.events;

public record CompensationResultEvent(String transactionId, boolean success) {}