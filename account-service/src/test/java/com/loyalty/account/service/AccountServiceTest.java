package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.exception.AccountAccessDeniedException;
import com.loyalty.account.exception.AccountNotFoundException;
import com.loyalty.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository);
    }

    @Test
    void createPersistsAccountWithOwnerActiveStatusAndZeroBalance() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account created = accountService.create("user-1");

        assertThat(created.getOwnerId()).isEqualTo("user-1");
        assertThat(created.getBalance()).isZero();
        assertThat(created.getMinBalance()).isZero();
        assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).matches("acc-\\d{10}");
    }

    @Test
    void createRetriesWithNewNumberWhenGeneratedIdAlreadyExists() {
        when(accountRepository.existsById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account created = accountService.create("user-1");

        assertThat(created.getId()).matches("acc-\\d{10}");
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.times(2)).existsById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getByIdForRequesterReturnsAccountWhenOwnerMatches() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        Account result = accountService.getByIdForRequester("acc-1", "user-1", false);

        assertThat(result).isEqualTo(account);
    }

    @Test
    void getByIdForRequesterReturnsAccountForAdminRegardlessOfOwner() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        Account result = accountService.getByIdForRequester("acc-1", "another-user", true);

        assertThat(result).isEqualTo(account);
    }

    @Test
    void getByIdForRequesterThrowsAccessDeniedWhenNotOwnerAndNotAdmin() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getByIdForRequester("acc-1", "another-user", false))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    void getByIdForRequesterThrowsNotFoundWhenAccountMissing() {
        when(accountRepository.findById("acc-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getByIdForRequester("acc-missing", "user-1", false))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void updateMinBalancePersistsNewLimit() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account updated = accountService.updateMinBalance("acc-1", -1000L);

        assertThat(updated.getMinBalance()).isEqualTo(-1000L);
    }

    @Test
    void updateStatusPersistsNewStatus() {
        Account account = accountWith("acc-1", "user-1");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account updated = accountService.updateStatus("acc-1", AccountStatus.INACTIVE);

        assertThat(updated.getStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    private Account accountWith(String id, String ownerId) {
        Account account = new Account();
        account.setId(id);
        account.setOwnerId(ownerId);
        account.setBalance(100L);
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }
}