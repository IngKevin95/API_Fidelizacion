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
    public boolean debitIfSufficientBalance(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null || before.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }

        CriteriaDefinition minBalanceExpr = new CriteriaDefinition() {
            @Override
            public Document getCriteriaObject() {
                return new Document("$expr", new Document("$gte", List.of(
                        new Document("$subtract", List.of("$balance", amount)),
                        new Document("$ifNull", List.of("$minBalance", 0L))
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
        }
        return applied;
    }

    @Override
    @Transactional("transactionManager")
    public boolean creditIfActive(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return false;
        }

        Query query = new Query(Criteria.where("_id").is(accountId).and("status").is(AccountStatus.ACTIVE));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            long balanceAfter = before.getBalance() + amount;
            boolean isFirstCredit = ledgerRepository.countByAccountId(accountId) == 0;
            BalanceLedgerEventType eventType = isFirstCredit ? BalanceLedgerEventType.SEED : BalanceLedgerEventType.CREDIT;
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
