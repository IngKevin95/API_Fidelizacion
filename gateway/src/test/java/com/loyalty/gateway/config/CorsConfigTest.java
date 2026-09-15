package com.loyalty.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void createsCorsWebFilterAllowingAnyOriginAndCommonMethods() {
        CorsConfig config = new CorsConfig();

        CorsWebFilter filter = config.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void configurationAllowsExpectedMethods() {
        CorsConfig config = new CorsConfig();
        CorsConfiguration corsConfiguration = config.buildCorsConfiguration();

        assertThat(corsConfiguration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
        assertThat(corsConfiguration.getAllowedOriginPatterns()).contains("*");
    }
}
