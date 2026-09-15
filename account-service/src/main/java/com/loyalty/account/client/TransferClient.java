package com.loyalty.account.client;

import com.loyalty.account.dto.TransactionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class TransferClient {

    private final RestClient restClient;

    @Autowired
    public TransferClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("http://transfer-service").build();
    }

    public TransferClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<TransactionView> listTransactions(String accountId, String bearerToken) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/transactions").queryParam("accountId", accountId).build())
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<TransactionView>>() {
                });
    }
}
