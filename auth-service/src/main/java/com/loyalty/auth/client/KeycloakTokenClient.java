package com.loyalty.auth.client;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class KeycloakTokenClient {

    private final RestClient restClient;
    private final String realm;
    private final String clientId;

    @Autowired
    public KeycloakTokenClient(RestClient.Builder restClientBuilder,
                                @Value("${keycloak.base-url}") String baseUrl,
                                @Value("${keycloak.realm}") String realm,
                                @Value("${keycloak.login-client-id}") String clientId) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
    }

    public KeycloakTokenClient(RestClient restClient, String realm, String clientId) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
    }

    @SuppressWarnings("unchecked")
    public TokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", username);
        form.add("password", password);

        Map<String, Object> response = restClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> status.value() == 401, (req, res) -> {
                    throw new InvalidCredentialsException("Usuario o contrasena incorrectos");
                })
                .body(Map.class);

        return new TokenResponse(
                (String) response.get("access_token"),
                (String) response.get("refresh_token"),
                ((Number) response.get("expires_in")).longValue());
    }
}