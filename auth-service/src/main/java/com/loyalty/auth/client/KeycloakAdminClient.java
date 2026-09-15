package com.loyalty.auth.client;

import com.loyalty.auth.exception.KeycloakUnavailableException;
import com.loyalty.auth.exception.UserAlreadyExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    @Autowired
    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                                @Value("${keycloak.base-url}") String baseUrl,
                                @Value("${keycloak.realm}") String realm,
                                @Value("${keycloak.admin-client-id}") String clientId,
                                @Value("${keycloak.admin-client-secret}") String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public KeycloakAdminClient(RestClient restClient, String realm, String clientId, String clientSecret) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void createUser(String username, String email, String password) {
        String serviceToken = obtainServiceToken();

        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "email", email,
                            "enabled", true,
                            "emailVerified", true,
                            "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false))
                    ))
                    .retrieve()
                    .onStatus(status -> status.value() == 409, (req, res) -> {
                        throw new UserAlreadyExistsException("El usuario '" + username + "' ya existe");
                    })
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new KeycloakUnavailableException("No se pudo crear el usuario en Keycloak: " + ex.getMessage());
        }

        assignUserRole(serviceToken, username);
    }

    private String obtainServiceToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return (String) response.get("access_token");
        } catch (RestClientException ex) {
            throw new KeycloakUnavailableException("No se pudo obtener token de servicio: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void assignUserRole(String serviceToken, String username) {
        List<Map<String, Object>> users = restClient.get()
                .uri("/admin/realms/{realm}/users?username={username}", realm, username)
                .header("Authorization", "Bearer " + serviceToken)
                .retrieve()
                .body(List.class);

        String userId = (String) users.get(0).get("id");

        Map<String, Object> userRole = restClient.get()
                .uri("/admin/realms/{realm}/roles/USER", realm)
                .header("Authorization", "Bearer " + serviceToken)
                .retrieve()
                .body(Map.class);

        restClient.post()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", realm, userId)
                .header("Authorization", "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(userRole))
                .retrieve()
                .toBodilessEntity();
    }
}