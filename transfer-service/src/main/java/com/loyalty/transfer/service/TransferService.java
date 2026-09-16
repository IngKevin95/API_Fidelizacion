package com.loyalty.transfer.service;

import com.loyalty.transfer.domain.Transaction;

public interface TransferService {

    Transaction initiate(String sourceAccountId, String targetAccountId, long amount, String requesterId, String bearerToken);
}
