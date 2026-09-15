package com.loyalty.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void createsCorsWebFilterAllowingConfiguredOriginsAndCommonMethods() {
        CorsConfig config = new CorsConfig("http://localhost:3000,https://app.example.com");

        CorsWebFilter filter = config.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void configurationAllowsExpectedMethodsAndConfiguredOriginsWithoutCredentials() {
        CorsConfig config = new CorsConfig("http://localhost:3000,https://app.example.com");
        CorsConfiguration corsConfiguration = config.buildCorsConfiguration();

        assertThat(corsConfiguration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
        assertThat(corsConfiguration.getAllowedOriginPatterns())
                .containsExactlyInAnyOrder("http://localhost:3000", "https://app.example.com");
        assertThat(corsConfiguration.getAllowCredentials()).isFalse();
    }
}
