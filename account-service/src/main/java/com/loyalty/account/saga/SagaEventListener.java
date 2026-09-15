package com.loyalty.account.saga;

import com.loyalty.account.repository.AccountRepository;
import com.loyalty.account.saga.events.CompensateDebitEvent;
import com.loyalty.account.saga.events.CreditRequestedEvent;
import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitRequestedEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SagaEventListener {

    private final AccountRepository accountRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaEventPublisher publisher;

    public SagaEventListener(AccountRepository accountRepository,
                              ProcessedEventRepository processedEventRepository,
                              SagaEventPublisher publisher) {
        this.accountRepository = accountRepository;
        this.processedEventRepository = processedEventRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "debit-events", groupId = "account-service-debit",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.DebitRequestedEvent")
    public void onDebitRequested(DebitRequestedEvent event) {
        if (event.sourceAccountId() == null) {
            return;
        }
        String idempotencyKey = event.transactionId() + ":DEBIT";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }

        boolean debited = accountRepository.debitIfSufficientBalance(event.sourceAccountId(), event.amount(), event.transactionId());
        if (debited) {
            publisher.publishDebitResult(DebitResultEvent.succeeded(event.transactionId()));
        } else {
            publisher.publishDebitResult(DebitResultEvent.failed(event.transactionId(), "INSUFFICIENT_BALANCE"));
        }
    }

    @KafkaListener(topics = "credit-events", groupId = "account-service-credit",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.CreditRequestedEvent")
    public void onCreditRequested(CreditRequestedEvent event) {
        if (event.targetAccountId() == null) {
            return;
        }
        String idempotencyKey = event.transactionId() + ":CREDIT";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }

        boolean credited = accountRepository.creditIfActive(event.targetAccountId(), event.amount(), event.transactionId());
        if (credited) {
            publisher.publishCreditResult(CreditResultEvent.succeeded(event.transactionId()));
        } else {
            publisher.publishCreditResult(CreditResultEvent.failed(event.transactionId(), "TARGET_INACTIVE"));
        }
    }

    @KafkaListener(topics = "transfer-compensation", groupId = "account-service-compensation",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.CompensateDebitEvent")
    public void onCompensateDebit(CompensateDebitEvent event) {
        String idempotencyKey = event.transactionId() + ":COMPENSATION";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }
        accountRepository.creditUnconditionally(event.sourceAccountId(), event.amount(), event.transactionId());
    }

    private boolean tryMarkProcessed(String idempotencyKey) {
        try {
            processedEventRepository.insert(new ProcessedEvent(idempotencyKey));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}