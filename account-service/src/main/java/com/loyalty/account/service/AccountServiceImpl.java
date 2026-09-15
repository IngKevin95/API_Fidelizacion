package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.exception.AccountAccessDeniedException;
import com.loyalty.account.exception.AccountNotFoundException;
import com.loyalty.account.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public Account create(String ownerId) {
        Account account = new Account();
        account.setId("acc-" + UUID.randomUUID());
        account.setOwnerId(ownerId);
        account.setBalance(0L);
        account.setMinBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    @Override
    public Account getByIdForRequester(String accountId, String requesterId, boolean isAdmin) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        if (!isAdmin && !account.getOwnerId().equals(requesterId)) {
            throw new AccountAccessDeniedException("No autorizado para acceder a la cuenta " + accountId);
        }

        return account;
    }

    @Override
    public Account updateStatus(String accountId, AccountStatus newStatus) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        account.setStatus(newStatus);
        account.setUpdatedAt(Instant.now());
        return accountRepository.save(account);
    }

    @Override
    public Account updateMinBalance(String accountId, Long minBalance) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Cuenta no encontrada: " + accountId));

        account.setMinBalance(minBalance);
        account.setUpdatedAt(Instant.now());
        return accountRepository.save(account);
    }
}