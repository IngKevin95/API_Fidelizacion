package com.loyalty.transfer.saga;

import com.loyalty.transfer.saga.events.CompensateDebitEvent;
import com.loyalty.transfer.saga.events.CreditRequestedEvent;
import com.loyalty.transfer.saga.events.DebitRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransferSagaPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishesDebitRequestedToDebitEventsTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishDebitRequested("tx-1", "acc-1", 50L);

        ArgumentCaptor<DebitRequestedEvent> captor = ArgumentCaptor.forClass(DebitRequestedEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("debit-events"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().sourceAccountId()).isEqualTo("acc-1");
        assertThat(captor.getValue().amount()).isEqualTo(50L);
    }

    @Test
    void publishesCreditRequestedToCreditEventsTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishCreditRequested("tx-1", "acc-1", "acc-2", 50L);

        ArgumentCaptor<CreditRequestedEvent> captor = ArgumentCaptor.forClass(CreditRequestedEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("credit-events"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().sourceAccountId()).isEqualTo("acc-1");
        assertThat(captor.getValue().targetAccountId()).isEqualTo("acc-2");
    }

    @Test
    void publishesCompensateDebitToCompensationTopic() {
        TransferSagaPublisher publisher = new TransferSagaPublisher(kafkaTemplate);

        publisher.publishCompensateDebit("tx-1", "acc-1", 50L);

        ArgumentCaptor<CompensateDebitEvent> captor = ArgumentCaptor.forClass(CompensateDebitEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("transfer-compensation"), org.mockito.ArgumentMatchers.eq("tx-1"), captor.capture());
        assertThat(captor.getValue().sourceAccountId()).isEqualTo("acc-1");
    }
}
