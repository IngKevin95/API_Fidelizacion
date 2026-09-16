package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import com.loyalty.account.domain.BalanceLedgerEntry;
import com.loyalty.account.domain.BalanceLedgerEventType;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Repository
public class AccountAtomicOperationsImpl implements AccountAtomicOperations {

    private final MongoTemplate mongoTemplate;
    private final BalanceLedgerRepository ledgerRepository;

    public AccountAtomicOperationsImpl(MongoTemplate mongoTemplate, BalanceLedgerRepository ledgerRepository) {
        this.mongoTemplate = mongoTemplate;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    @Transactional("transactionManager")
    public DebitOutcome debitIfSufficientBalance(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return DebitOutcome.failure("ACCOUNT_NOT_FOUND");
        }
        if (before.getStatus() != AccountStatus.ACTIVE) {
            return DebitOutcome.failure("SOURCE_INACTIVE");
        }

        CriteriaDefinition minBalanceExpr = new CriteriaDefinition() {
            @Override
            public Document getCriteriaObject() {
                // minBalance == null significa "sin piso" (ej. acc-treasury): la cuenta
                // puede ir arbitrariamente negativa. No confundir con minBalance == 0
                // (piso normal de una cuenta comun, que si bloquea el debito).
                return new Document("$expr", new Document("$or", List.of(
                        new Document("$eq", Arrays.asList("$minBalance", null)),
                        new Document("$gte", List.of(
                                new Document("$subtract", List.of("$balance", amount)),
                                "$minBalance"
                        ))
                )));
            }

            @Override
            public String getKey() {
                return "$expr";
            }
        };

        Query query = new Query(Criteria.where("_id").is(accountId).and("status").is(AccountStatus.ACTIVE));
        query.addCriteria(minBalanceExpr);
        Update update = new Update().inc("balance", -amount).set("updatedAt", Instant.now());

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            long balanceAfter = before.getBalance() - amount;
            ledgerRepository.save(new BalanceLedgerEntry(accountId, BalanceLedgerEventType.DEBIT, transactionId,
                    -amount, before.getBalance(), balanceAfter));
            return DebitOutcome.success();
        }
        return DebitOutcome.failure("INSUFFICIENT_BALANCE");
    }

    @Override
    @Transactional("transactionManager")
    public boolean creditIfActive(String accountId, long amount, String transactionId, boolean fromTreasury) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return false;
        }

        Query query = new Query(Criteria.where("_id").is(accountId).and("status").is(AccountStatus.ACTIVE));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            long balanceAfter = before.getBalance() + amount;
            BalanceLedgerEventType eventType = fromTreasury ? BalanceLedgerEventType.SEED : BalanceLedgerEventType.CREDIT;
            ledgerRepository.save(new BalanceLedgerEntry(accountId, eventType, transactionId,
                    amount, before.getBalance(), balanceAfter));
        }
        return applied;
    }

    @Override
    @Transactional("transactionManager")
    public void creditUnconditionally(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return;
        }

        Query query = new Query(Criteria.where("_id").is(accountId));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        mongoTemplate.updateFirst(query, update, Account.class);

        long balanceAfter = before.getBalance() + amount;
        ledgerRepository.save(new BalanceLedgerEntry(accountId, BalanceLedgerEventType.COMPENSATION, transactionId,
                amount, before.getBalance(), balanceAfter));
    }
}
