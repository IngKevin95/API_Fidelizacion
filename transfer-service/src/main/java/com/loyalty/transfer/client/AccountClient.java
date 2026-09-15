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
        this.restClient = restClientBuilder.baseUrl("http://account-service").build();
    }

    public AccountClient(RestClient restClient) {
        this.restClient = restClient;
    }

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
}