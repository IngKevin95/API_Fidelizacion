package com.loyalty.account.client;

import com.loyalty.account.dto.TransactionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
public class TransferClient {

    private final RestClient restClient;

    @Autowired
    public TransferClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder
                .baseUrl("http://transfer-service")
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    public TransferClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "transferService", fallbackMethod = "listTransactionsFallback")
    public List<TransactionView> listTransactions(String accountId, String bearerToken) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/transactions").queryParam("accountId", accountId).build())
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<TransactionView>>() {
                });
    }

    public List<TransactionView> listTransactionsFallback(String accountId, String bearerToken, Throwable t) {
        throw new com.loyalty.account.exception.TransferServiceUnavailableException("El servicio de transferencias no está disponible");
    }
}
