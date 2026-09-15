package com.loyalty.account.service;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;

public interface AccountService {

    Account create(String ownerId, Long initialBalance);

    Account getByIdForRequester(String accountId, String requesterId, boolean isAdmin);

    Account updateStatus(String accountId, AccountStatus newStatus);
}