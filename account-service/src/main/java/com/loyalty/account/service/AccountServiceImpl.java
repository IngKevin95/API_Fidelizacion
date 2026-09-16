package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.exception.AccountAccessDeniedException;
import com.loyalty.account.exception.AccountNotFoundException;
import com.loyalty.account.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;

@Service
public class AccountServiceImpl implements AccountService {

    private static final long ACCOUNT_NUMBER_MIN = 1_000_000_000L;
    private static final long ACCOUNT_NUMBER_RANGE = 9_000_000_000L;

    private final AccountRepository accountRepository;
    private final SecureRandom random = new SecureRandom();

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public Account create(String ownerId) {
        Account account = new Account();
        account.setId(generateAccountId());
        account.setOwnerId(ownerId);
        account.setBalance(0L);
        account.setMinBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    private String generateAccountId() {
        String candidate;
        do {
            long number = ACCOUNT_NUMBER_MIN + (long) (random.nextDouble() * ACCOUNT_NUMBER_RANGE);
            candidate = "acc-" + number;
        } while (accountRepository.existsById(candidate));
        return candidate;
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