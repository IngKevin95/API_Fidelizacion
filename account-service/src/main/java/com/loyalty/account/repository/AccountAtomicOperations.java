package com.loyalty.account.repository;

public interface AccountAtomicOperations {

    boolean debitIfSufficientBalance(String accountId, long amount, String transactionId);

    boolean creditIfActive(String accountId, long amount, String transactionId);

    void creditUnconditionally(String accountId, long amount, String transactionId);
}
