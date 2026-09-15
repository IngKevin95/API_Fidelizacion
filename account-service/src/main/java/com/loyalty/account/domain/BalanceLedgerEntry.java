package com.loyalty.account.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document(collection = "balance_ledger")
public class BalanceLedgerEntry {

    @Id
    private String id;
    private String accountId;
    private BalanceLedgerEventType eventType;
    private String transactionId;
    private long delta;
    private long balanceBefore;
    private long balanceAfter;
    private Instant createdAt;

    protected BalanceLedgerEntry() {
    }

    public BalanceLedgerEntry(String accountId, BalanceLedgerEventType eventType, String transactionId,
                               long delta, long balanceBefore, long balanceAfter) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.eventType = eventType;
        this.transactionId = transactionId;
        this.delta = delta;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
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
