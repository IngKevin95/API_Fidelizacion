package com.loyalty.account;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.repository.AccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TreasurySeedRunner implements ApplicationRunner {

    private static final String TREASURY_ACCOUNT_ID = "acc-treasury";

    private final AccountRepository accountRepository;

    public TreasurySeedRunner(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.existsById(TREASURY_ACCOUNT_ID)) {
            return;
        }

        Account treasury = new Account();
        treasury.setId(TREASURY_ACCOUNT_ID);
        treasury.setOwnerId("system");
        treasury.setBalance(0L);
        treasury.setMinBalance(null);
        treasury.setStatus(AccountStatus.ACTIVE);
        Instant now = Instant.now();
        treasury.setCreatedAt(now);
        treasury.setUpdatedAt(now);
        accountRepository.save(treasury);
    }
}
