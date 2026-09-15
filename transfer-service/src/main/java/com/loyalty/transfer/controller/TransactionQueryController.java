package com.loyalty.transfer.controller;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.dto.TransactionResponse;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionQueryController {

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;

    public TransactionQueryController(TransactionRepository transactionRepository, AccountClient accountClient) {
        this.transactionRepository = transactionRepository;
        this.accountClient = accountClient;
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listByAccount(@RequestParam("accountId") String accountId,
                                                     Authentication authentication) {
        String bearerToken = ((JwtAuthenticationToken) authentication).getToken().getTokenValue();
        accountClient.fetchAccount(accountId, bearerToken);

        return transactionRepository.findBySourceAccountIdOrTargetAccountId(accountId, accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
