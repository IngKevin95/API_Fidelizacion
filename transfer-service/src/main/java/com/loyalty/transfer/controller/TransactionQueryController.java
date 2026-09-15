package com.loyalty.transfer.controller;

import com.loyalty.transfer.dto.TransactionResponse;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionQueryController {

    private final TransactionRepository transactionRepository;

    public TransactionQueryController(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listByAccount(@RequestParam("accountId") String accountId) {
        return transactionRepository.findBySourceAccountIdOrTargetAccountId(accountId, accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}