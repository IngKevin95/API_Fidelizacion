package com.loyalty.account.repository;

import com.loyalty.account.domain.Account;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String>, AccountAtomicOperations {
}