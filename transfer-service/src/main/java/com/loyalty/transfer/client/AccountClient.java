package com.loyalty.transfer.client;

import com.loyalty.transfer.dto.AccountView;
import com.loyalty.transfer.exception.AccountNotFoundException;
import com.loyalty.transfer.exception.TransferAccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AccountClient {

    private final RestClient restClient;

    @Autowired
    public AccountClient(RestClient.Builder restClientBuilder) {
        org.springframework.boot.web.client.ClientHttpRequestFactorySettings settings = org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(java.time.Duration.ofSeconds(3))
                .withReadTimeout(java.time.Duration.ofSeconds(5));
        this.restClient = restClientBuilder
                .baseUrl("http://account-service")
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(settings))
                .build();
    }

    public AccountClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "accountService", fallbackMethod = "fetchAccountFallback")
    public AccountView fetchAccount(String accountId, String bearerToken) {
        return restClient.get()
                .uri("/accounts/{id}", accountId)
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        throw new AccountNotFoundException("Cuenta no encontrada: " + accountId);
                    }
                    if (response.getStatusCode().value() == 403) {
                        throw new TransferAccessDeniedException("No autorizado para acceder a la cuenta " + accountId);
                    }
                })
                .body(AccountView.class);
    }

    public AccountView fetchAccountFallback(String accountId, String bearerToken, Throwable t) {
        if (t instanceof AccountNotFoundException || t instanceof TransferAccessDeniedException) {
            throw (RuntimeException) t;
        }
        throw new com.loyalty.transfer.exception.AccountServiceUnavailableException("El servicio de cuentas no está disponible");
    }
}