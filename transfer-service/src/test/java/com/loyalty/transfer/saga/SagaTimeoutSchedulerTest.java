package com.loyalty.transfer.saga;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaTimeoutSchedulerTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Test
    void marksStaleTransactionsAsFailedWithTimeoutReason() {
        Transaction stale = new Transaction();
        stale.setId("tx-old");
        stale.setStatus(TransactionStatus.PENDING);
        stale.setCreatedAt(Instant.now().minusSeconds(60));

        when(transactionRepository.findByStatusAndCreatedAtBefore(any(TransactionStatus.class), any(Instant.class)))
                .thenReturn(List.of(stale));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        SagaTimeoutScheduler scheduler = new SagaTimeoutScheduler(transactionRepository, 30L);
        scheduler.expireStaleTransactions();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("SAGA_TIMEOUT");
    }
}
