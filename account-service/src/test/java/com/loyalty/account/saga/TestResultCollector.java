package com.loyalty.account.saga;

import com.loyalty.account.saga.events.CompensationResultEvent;
import com.loyalty.account.saga.events.CreditResultEvent;
import com.loyalty.account.saga.events.DebitResultEvent;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@TestComponent
public class TestResultCollector {

    private final LinkedBlockingQueue<DebitResultEvent> debitResults = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<CreditResultEvent> creditResults = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<CompensationResultEvent> compensationResults = new LinkedBlockingQueue<>();

    @KafkaListener(topics = "debit-results", groupId = "test-collector-debit",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.DebitResultEvent")
    public void onDebitResult(DebitResultEvent event) {
        debitResults.add(event);
    }

    @KafkaListener(topics = "credit-results", groupId = "test-collector-credit",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.CreditResultEvent")
    public void onCreditResult(CreditResultEvent event) {
        creditResults.add(event);
    }

    @KafkaListener(topics = "compensation-results", groupId = "test-collector-compensation",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.CompensationResultEvent")
    public void onCompensationResult(CompensationResultEvent event) {
        compensationResults.add(event);
    }

    public DebitResultEvent pollDebitResult(Duration timeout) throws InterruptedException {
        return debitResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CreditResultEvent pollCreditResult(Duration timeout) throws InterruptedException {
        return creditResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CompensationResultEvent pollCompensationResult(Duration timeout) throws InterruptedException {
        return compensationResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void clear() {
        debitResults.clear();
        creditResults.clear();
        compensationResults.clear();
    }
}