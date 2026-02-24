package com.platform.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration allowing the React dashboard (localhost:3000 in dev,
 * Amplify URL in production) to call the API without browser blocking.
 *
 * Origins are driven by app.cors.allowed-origins in application.yml so
 * no code change is needed when adding new deployment environments.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Explicitly list allowed origins (no wildcard — required when credentials are sent)
        config.setAllowedOrigins(allowedOrigins);

        // Standard REST + SSE methods used by the dashboard
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));

        // Headers the frontend sends
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Correlation-Id",
                "X-Request-Id",
                "Cache-Control"
        ));

        // Headers the frontend may read from responses
        config.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Request-Id",
                "X-Total-Count"
        ));

        config.setAllowCredentials(true);

        // Cache preflight for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);

        return new CorsFilter(source);
    }
}
