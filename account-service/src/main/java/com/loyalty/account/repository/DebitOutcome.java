package com.loyalty.account.repository;

public record DebitOutcome(boolean isSuccess, String reason) {

    public static DebitOutcome success() {
        return new DebitOutcome(true, null);
    }

    public static DebitOutcome failure(String reason) {
        return new DebitOutcome(false, reason);
    }
}