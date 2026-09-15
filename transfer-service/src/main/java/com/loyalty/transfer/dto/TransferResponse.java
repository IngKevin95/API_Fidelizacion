package com.loyalty.transfer.dto;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;

import java.time.Instant;

public class TransferResponse {

    private String transactionId;
    private TransactionStatus status;
    private Instant createdAt;

    public static TransferResponse from(Transaction transaction) {
        TransferResponse response = new TransferResponse();
        response.transactionId = transaction.getId();
        response.status = transaction.getStatus();
        response.createdAt = transaction.getCreatedAt();
        return response;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
