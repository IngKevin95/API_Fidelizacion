package com.loyalty.account.repository;

public interface AccountAtomicOperations {

    DebitOutcome debitIfSufficientBalance(String accountId, long amount, String transactionId);

    boolean creditIfActive(String accountId, long amount, String transactionId, boolean fromTreasury);

    void creditUnconditionally(String accountId, long amount, String transactionId);
}
