package com.platform.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS filter for the REST API and SSE stream endpoints.
 *
 * Fixes vs. original:
 *  - allowCredentials set to FALSE. Browsers do not send cookies on EventSource
 *    connections; having it true caused the SSE stream to be blocked by the
 *    browser even when single POST requests worked fine.
 *  - Added "Last-Event-ID" to allowed headers (EventSource sends this on reconnect).
 *  - Added "Accept-Encoding" to allowed headers (sent automatically by fetch).
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Accept-Encoding",
                "X-Correlation-Id",
                "X-Request-Id",
                "Cache-Control",
                "Last-Event-ID"
        ));
        config.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Request-Id",
                "X-Total-Count"
        ));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);
        return new CorsFilter(source);
    }
}
