package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class SagaTimeoutScheduler {

    private final TransactionRepository transactionRepository;
    private final long timeoutSeconds;

    public SagaTimeoutScheduler(TransactionRepository transactionRepository,
                                 @Value("${transfer.saga.timeout-seconds}") long timeoutSeconds) {
        this.transactionRepository = transactionRepository;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelay = 10000)
    public void expireStaleTransactions() {
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);
        List<Transaction> stale = transactionRepository.findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, threshold);

        for (Transaction transaction : stale) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("SAGA_TIMEOUT");
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }
}
