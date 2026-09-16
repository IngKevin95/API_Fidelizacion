package com.loyalty.account.repository;

import com.loyalty.account.domain.BalanceLedgerEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BalanceLedgerRepository extends MongoRepository<BalanceLedgerEntry, String> {

    List<BalanceLedgerEntry> findByAccountIdOrderByCreatedAtAsc(String accountId);

    long countByAccountId(String accountId);
}
