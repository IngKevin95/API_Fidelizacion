package com.loyalty.account.dto;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;

import java.time.Instant;

public class AccountResponse {

    private String id;
    private String ownerId;
    private long balance;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public static AccountResponse from(Account account) {
        AccountResponse response = new AccountResponse();
        response.id = account.getId();
        response.ownerId = account.getOwnerId();
        response.balance = account.getBalance();
        response.status = account.getStatus();
        response.createdAt = account.getCreatedAt();
        response.updatedAt = account.getUpdatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
