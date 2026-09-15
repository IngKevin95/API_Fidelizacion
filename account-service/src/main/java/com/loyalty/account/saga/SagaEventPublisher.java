package com.loyalty.account.saga;

import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SagaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SagaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDebitResult(DebitResultEvent event) {
        kafkaTemplate.send("debit-results", event.transactionId(), event);
    }

    public void publishCreditResult(CreditResultEvent event) {
        kafkaTemplate.send("credit-results", event.transactionId(), event);
    }
}