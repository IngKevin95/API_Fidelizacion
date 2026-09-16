package com.loyalty.transfer.service;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.SameAccountException;
import com.loyalty.transfer.exception.TransferUnprocessableException;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.TransferSagaPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountClient accountClient;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransferSagaPublisher sagaPublisher;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferServiceImpl(accountClient, transactionRepository, sagaPublisher);
    }

    @Test
    void rejectsSameSourceAndTargetAccountWithoutCallingAccountService() {
        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-1", 10L, "user-1", "token"))
                .isInstanceOf(SameAccountException.class);

        verifyNoInteractions(accountClient);
    }

    @Test
    void rejectsWhenSourceBalanceInsufficient() {
        AccountView source = accountView("acc-1", "user-1", 5L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_BALANCE");
    }

    @Test
    void rejectsWhenSourceInactive() {
        AccountView source = accountView("acc-1", "user-1", 100L, "INACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "SOURCE_INACTIVE");
    }

    @Test
    void rejectsWhenTargetInactive() {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "INACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "TARGET_INACTIVE");
    }

    @Test
    void persistsPendingTransactionAndPublishesDebitRequestedWhenValid() {
        AccountView source = accountView("acc-1", "user-1", 100L, "ACTIVE");
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.getSourceAccountId()).isEqualTo("acc-1");
        verify(sagaPublisher).publishDebitRequested(anyString(), org.mockito.ArgumentMatchers.eq("acc-1"), anyLong());
    }

    @Test
    void allowsTransferBelowZeroWhenMinBalanceIsNegative() {
        AccountView source = accountView("acc-treasury", "system", 0L, "ACTIVE");
        source.setMinBalance(-1_000_000L);
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-treasury", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transferService.initiate("acc-treasury", "acc-2", 500L, "admin-1", "token");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void allowsTransferArbitrarilyBelowZeroWhenMinBalanceIsNull() {
        AccountView source = accountView("acc-treasury", "system", 0L, "ACTIVE");
        source.setMinBalance(null);
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-treasury", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transferService.initiate("acc-treasury", "acc-2", 500L, "admin-1", "token");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void rejectsWhenResultWouldGoBelowMinBalance() {
        AccountView source = accountView("acc-1", "user-1", 10L, "ACTIVE");
        source.setMinBalance(0L);
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_BALANCE");
    }

    private AccountView accountView(String id, String ownerId, long balance, String status) {
        AccountView view = new AccountView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setBalance(balance);
        view.setMinBalance(0L);
        view.setStatus(status);
        return view;
    }
}
