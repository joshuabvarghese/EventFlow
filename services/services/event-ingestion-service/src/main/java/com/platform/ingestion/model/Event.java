package com.platform.ingestion.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event model representing any event in the system.
 * Uses Java 17 records for immutability and concise syntax.
 */
@Builder
@Jacksonized
public record Event(
        @NotBlank(message = "Event ID is required")
        String eventId,

        @NotBlank(message = "Event type is required")
        String eventType,

        @NotBlank(message = "User ID is required")
        String userId,

        @NotNull(message = "Timestamp is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant timestamp,

        JsonNode data,

        Map<String, String> metadata,

        String correlationId,

        String source,

        String version
) {
    /**
     * Compact constructor for validation and defaults
     */
    public Event {
        if (eventId == null || eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (version == null || version.isBlank()) {
            version = "1.0";
        }
        if (source == null || source.isBlank()) {
            source = "api";
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = eventId;
        }
    }

    /**
     * Factory method for creating events
     */
    public static Event create(String eventType, String userId, JsonNode data) {
        return Event.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .userId(userId)
                .timestamp(Instant.now())
                .data(data)
                .version("1.0")
                .source("api")
                .build();
    }

    /**
     * Check if this is a critical event that requires special handling
     */
    public boolean isCritical() {
        return eventType.startsWith("error.") ||
               eventType.startsWith("security.") ||
               eventType.startsWith("fraud.");
    }

    /**
     * Get event category from event type
     */
    public String getCategory() {
        return eventType.split("\\.")[0];
    }
}
