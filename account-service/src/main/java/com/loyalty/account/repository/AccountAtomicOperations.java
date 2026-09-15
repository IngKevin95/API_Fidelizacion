package com.loyalty.account.repository;

public interface AccountAtomicOperations {

    boolean debitIfSufficientBalance(String accountId, long amount);

    boolean creditIfActive(String accountId, long amount);

    void creditUnconditionally(String accountId, long amount);
}