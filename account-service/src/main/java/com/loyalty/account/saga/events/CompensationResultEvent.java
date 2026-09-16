package com.loyalty.account.saga.events;

public record CompensationResultEvent(String transactionId, boolean success) {}