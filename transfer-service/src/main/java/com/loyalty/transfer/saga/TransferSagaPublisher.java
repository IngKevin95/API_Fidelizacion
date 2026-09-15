package com.loyalty.transfer.saga;

import com.loyalty.transfer.saga.events.CompensateDebitEvent;
import com.loyalty.transfer.saga.events.CreditRequestedEvent;
import com.loyalty.transfer.saga.events.DebitRequestedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TransferSagaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TransferSagaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDebitRequested(String transactionId, String sourceAccountId, long amount) {
        kafkaTemplate.send("debit-events", transactionId,
                new DebitRequestedEvent(transactionId, sourceAccountId, amount, Instant.now().toString()));
    }

    public void publishCreditRequested(String transactionId, String targetAccountId, long amount) {
        kafkaTemplate.send("credit-events", transactionId,
                new CreditRequestedEvent(transactionId, targetAccountId, amount, Instant.now().toString()));
    }

    public void publishCompensateDebit(String transactionId, String sourceAccountId, long amount) {
        kafkaTemplate.send("transfer-compensation", transactionId,
                new CompensateDebitEvent(transactionId, sourceAccountId, amount));
    }
}
