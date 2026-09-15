package com.loyalty.transfer.service;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.domain.TransactionStatus;
import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.SameAccountException;
import com.loyalty.transfer.exception.TransferUnprocessableException;
import com.loyalty.transfer.repository.TransactionRepository;
import com.loyalty.transfer.saga.TransferSagaPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransferServiceImpl implements TransferService {

    private final AccountClient accountClient;
    private final TransactionRepository transactionRepository;
    private final TransferSagaPublisher sagaPublisher;

    public TransferServiceImpl(AccountClient accountClient,
                                TransactionRepository transactionRepository,
                                TransferSagaPublisher sagaPublisher) {
        this.accountClient = accountClient;
        this.transactionRepository = transactionRepository;
        this.sagaPublisher = sagaPublisher;
    }

    @Override
    public Transaction initiate(String sourceAccountId, String targetAccountId, long amount,
                                 String requesterId, String bearerToken) {
        if (sourceAccountId.equals(targetAccountId)) {
            throw new SameAccountException("La cuenta origen y destino deben ser distintas");
        }

        AccountView source = accountClient.fetchAccount(sourceAccountId, bearerToken);
        AccountView target = accountClient.fetchAccount(targetAccountId, bearerToken);

        if (!"ACTIVE".equals(source.getStatus())) {
            throw new TransferUnprocessableException("SOURCE_INACTIVE", "La cuenta origen no esta activa");
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            throw new TransferUnprocessableException("TARGET_INACTIVE", "La cuenta destino no esta activa");
        }
        long effectiveMinBalance = source.getMinBalance() != null ? source.getMinBalance() : 0L;
        if (source.getBalance() - amount < effectiveMinBalance) {
            throw new TransferUnprocessableException("INSUFFICIENT_BALANCE", "Saldo insuficiente en la cuenta origen");
        }

        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID().toString());
        transaction.setSourceAccountId(sourceAccountId);
        transaction.setTargetAccountId(targetAccountId);
        transaction.setAmount(amount);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setCreatedAt(Instant.now());
        Transaction saved = transactionRepository.save(transaction);

        sagaPublisher.publishDebitRequested(saved.getId(), sourceAccountId, amount);

        return saved;
    }
}
