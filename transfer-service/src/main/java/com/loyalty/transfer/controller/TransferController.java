package com.loyalty.transfer.controller;

import com.loyalty.transfer.domain.Transaction;
import com.loyalty.transfer.dto.TransferRequest;
import com.loyalty.transfer.dto.TransferResponse;
import com.loyalty.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request,
                                                      Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        Jwt jwt = jwtAuth.getToken();

        Transaction transaction = transferService.initiate(
                request.getSourceAccountId(),
                request.getTargetAccountId(),
                request.getAmount(),
                jwt.getSubject(),
                jwt.getTokenValue());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TransferResponse.from(transaction));
    }
}
