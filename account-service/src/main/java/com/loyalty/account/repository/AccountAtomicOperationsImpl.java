package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import com.loyalty.account.domain.AccountStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class AccountAtomicOperationsImpl implements AccountAtomicOperations {

    private final MongoTemplate mongoTemplate;

    public AccountAtomicOperationsImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean debitIfSufficientBalance(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId)
                .and("status").is(AccountStatus.ACTIVE)
                .and("balance").gte(amount));
        Update update = new Update().inc("balance", -amount).set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
    }

    @Override
    public boolean creditIfActive(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId)
                .and("status").is(AccountStatus.ACTIVE));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
    }

    @Override
    public void creditUnconditionally(String accountId, long amount) {
        Query query = new Query(Criteria.where("_id").is(accountId));
        Update update = new Update().inc("balance", amount).set("updatedAt", Instant.now());

        mongoTemplate.updateFirst(query, update, Account.class);
    }
}