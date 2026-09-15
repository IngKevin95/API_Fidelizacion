package com.loyalty.transfer.dto;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;

import java.time.Instant;

public class TransactionResponse {

    private String id;
    private String sourceAccountId;
    private String targetAccountId;
    private long amount;
    private TransactionStatus status;
    private String failureReason;
    private Instant createdAt;
    private Instant completedAt;

    public static TransactionResponse from(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.id = transaction.getId();
        response.sourceAccountId = transaction.getSourceAccountId();
        response.targetAccountId = transaction.getTargetAccountId();
        response.amount = transaction.getAmount();
        response.status = transaction.getStatus();
        response.failureReason = transaction.getFailureReason();
        response.createdAt = transaction.getCreatedAt();
        response.completedAt = transaction.getCompletedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
