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
 * Amplify URL in production) to call the API and receive SSE streams
 * without browser blocking.
 *
 * Key fix: text/event-stream responses require the CORS filter to also
 * allow the "Accept" and "Cache-Control" headers that EventSource sends
 * automatically. Without this, the browser blocks the SSE connection
 * on the preflight check even though the GET itself would succeed.
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

        // Headers the frontend sends — "Accept" is critical for SSE:
        // EventSource sets Accept: text/event-stream automatically and the
        // browser blocks the connection if that header isn't in the allowed list.
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Accept-Encoding",
                "X-Correlation-Id",
                "X-Request-Id",
                "Cache-Control",
                "Last-Event-ID"   // EventSource sends this on reconnect
        ));

        // Headers the frontend may read from responses
        config.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Request-Id",
                "X-Total-Count"
        ));

        // Credentials must be false for SSE with EventSource — browsers do not
        // send cookies on EventSource connections, and allowCredentials=true with
        // a wildcard (or misconfigured) origin causes a CORS rejection on the
        // SSE stream even when single-event POST requests succeed.
        config.setAllowCredentials(false);

        // Cache preflight for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);

        return new CorsFilter(source);
    }
}
