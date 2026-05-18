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
 * Immutable event record.
 *
 * Uses a Java record so all fields are final by default.
 * @Builder + @Jacksonized lets Jackson deserialise via the Lombok-generated
 * builder rather than needing a no-arg constructor (records don't have one).
 */
@Builder
@Jacksonized
public record Event(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "eventType is required")
        String eventType,

        @NotBlank(message = "userId is required")
        String userId,

        @NotNull(message = "timestamp is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    timezone = "UTC")
        Instant timestamp,

        JsonNode data,

        Map<String, String> metadata,

        String correlationId,

        String source,

        String version

) {
    /** Compact constructor — fills in defaults for optional fields. */
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

    /** Convenience factory used in tests and internal tooling. */
    public static Event create(String eventType, String userId, JsonNode data) {
        return Event.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .userId(userId)
                .timestamp(Instant.now())
                .data(data)
                .build();
    }

    /** Critical events are routed to the high-priority Kafka topic. */
    public boolean isCritical() {
        return eventType.startsWith("error.")
                || eventType.startsWith("security.")
                || eventType.startsWith("fraud.");
    }

    /** Returns the dot-prefix category (e.g. "user" from "user.login"). */
    public String getCategory() {
        return eventType.split("\\.")[0];
    }
}
