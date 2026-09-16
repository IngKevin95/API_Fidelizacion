package com.loyalty.account.controller;

import com.loyalty.account.client.TransferClient;
import com.loyalty.account.domain.Account;
import com.loyalty.account.dto.AccountResponse;
import com.loyalty.account.dto.BalanceLedgerEntryResponse;
import com.loyalty.account.dto.CreateAccountRequest;
import com.loyalty.account.dto.TransactionView;
import com.loyalty.account.dto.UpdateLimitsRequest;
import com.loyalty.account.dto.UpdateStatusRequest;
import com.loyalty.account.repository.BalanceLedgerRepository;
import com.loyalty.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransferClient transferClient;
    private final BalanceLedgerRepository ledgerRepository;

    public AccountController(AccountService accountService, TransferClient transferClient,
                              BalanceLedgerRepository ledgerRepository) {
        this.accountService = accountService;
        this.transferClient = transferClient;
        this.ledgerRepository = ledgerRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                   Authentication authentication) {
        String ownerId = subjectOf(authentication);
        Account created = accountService.create(ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getById(@PathVariable("id") String id, Authentication authentication) {
        String requesterId = subjectOf(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        Account account = accountService.getByIdForRequester(id, requesterId, isAdmin);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionView>> getTransactions(@PathVariable("id") String id,
                                                                  Authentication authentication) {
        String requesterId = subjectOf(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        accountService.getByIdForRequester(id, requesterId, isAdmin);

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        String bearerToken = jwtAuth.getToken().getTokenValue();
        List<TransactionView> transactions = transferClient.listTransactions(id, bearerToken);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<BalanceLedgerEntryResponse>> getLedger(@PathVariable("id") String id,
                                                                       Authentication authentication) {
        String requesterId = subjectOf(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
        accountService.getByIdForRequester(id, requesterId, isAdmin);

        List<BalanceLedgerEntryResponse> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc(id).stream()
                .map(BalanceLedgerEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable("id") String id,
                                                         @Valid @RequestBody UpdateStatusRequest request) {
        Account updated = accountService.updateStatus(id, request.getStatus());
        return ResponseEntity.ok(AccountResponse.from(updated));
    }

    @PatchMapping("/{id}/limits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> updateLimits(@PathVariable("id") String id,
                                                         @Valid @RequestBody UpdateLimitsRequest request) {
        Account updated = accountService.updateMinBalance(id, request.getMinBalance());
        return ResponseEntity.ok(AccountResponse.from(updated));
    }

    private String subjectOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return jwt.getSubject();
        }
        throw new IllegalStateException("Autenticacion no es JWT");
    }

    private boolean hasRole(Authentication authentication, String role) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().equals(role)) {
                return true;
            }
        }
        return false;
    }
}
