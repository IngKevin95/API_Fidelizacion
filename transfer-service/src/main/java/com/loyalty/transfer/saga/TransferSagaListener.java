package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.events.CreditResultEvent;
import com.loyalty.transfer.saga.events.DebitResultEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransferSagaListener {

    private final TransactionRepository transactionRepository;
    private final TransferSagaPublisher publisher;

    public TransferSagaListener(TransactionRepository transactionRepository, TransferSagaPublisher publisher) {
        this.transactionRepository = transactionRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "debit-results", groupId = "transfer-service-debit-results",
            properties = "spring.json.value.default.type:com.loyalty.transfer.saga.events.DebitResultEvent")
    public void onDebitResult(DebitResultEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null || transaction.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        if (event.success()) {
            publisher.publishCreditRequested(transaction.getId(), transaction.getSourceAccountId(), transaction.getTargetAccountId(), transaction.getAmount());
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(event.reason());
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }

    @KafkaListener(topics = "credit-results", groupId = "transfer-service-credit-results",
            properties = "spring.json.value.default.type:com.loyalty.transfer.saga.events.CreditResultEvent")
    public void onCreditResult(CreditResultEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null || transaction.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        if (event.success()) {
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        } else {
            publisher.publishCompensateDebit(transaction.getId(), transaction.getSourceAccountId(), transaction.getAmount());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(event.reason());
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }
}
