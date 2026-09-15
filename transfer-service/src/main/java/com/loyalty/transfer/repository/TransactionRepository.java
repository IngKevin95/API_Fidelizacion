package com.loyalty.transfer.repository;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);

    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant threshold);
}