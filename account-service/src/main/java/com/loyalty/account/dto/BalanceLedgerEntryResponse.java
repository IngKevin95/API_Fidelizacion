package com.loyalty.account.dto;

import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;

import java.time.Instant;

public class BalanceLedgerEntryResponse {

    private String id;
    private String accountId;
    private BalanceLedgerEventType eventType;
    private String transactionId;
    private long delta;
    private long balanceBefore;
    private long balanceAfter;
    private Instant createdAt;

    public static BalanceLedgerEntryResponse from(BalanceLedgerEntry entry) {
        BalanceLedgerEntryResponse response = new BalanceLedgerEntryResponse();
        response.id = entry.getId();
        response.accountId = entry.getAccountId();
        response.eventType = entry.getEventType();
        response.transactionId = entry.getTransactionId();
        response.delta = entry.getDelta();
        response.balanceBefore = entry.getBalanceBefore();
        response.balanceAfter = entry.getBalanceAfter();
        response.createdAt = entry.getCreatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BalanceLedgerEventType getEventType() {
        return eventType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceBefore() {
        return balanceBefore;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
