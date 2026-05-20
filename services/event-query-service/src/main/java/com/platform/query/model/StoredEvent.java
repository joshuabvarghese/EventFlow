package com.platform.query.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code events} table created by init-db.sql.
 *
 * The {@code status} column is added via the alter-db.sql migration script.
 * It defaults to {@code 'processed'} at the DB level so existing rows
 * (if any) are never null.
 */
@Entity
@Table(name = "events",
       indexes = {
           @Index(name = "idx_events_event_type", columnList = "event_type"),
           @Index(name = "idx_events_user_id",    columnList = "user_id"),
           @Index(name = "idx_events_created_at", columnList = "created_at DESC"),
           @Index(name = "idx_events_status",     columnList = "status"),
           @Index(name = "idx_events_source_topic", columnList = "source_topic")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Matches the ingestion-service Event.eventId(). Must be unique. */
    @Column(name = "event_id", nullable = false, unique = true, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "source", length = 100)
    private String source;

    @Column(name = "version", length = 20)
    private String version;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    /**
     * Which Kafka topic this event arrived on.
     * Not in the original init-db.sql schema — added by alter-db.sql.
     */
    @Column(name = "source_topic", length = 100)
    private String sourceTopic;

    /**
     * Processing outcome: {@code processed}, {@code failed}, or {@code duplicate}.
     * Added by alter-db.sql; defaults to {@code 'processed'} at DB level.
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "processed";

    /**
     * Free-form event payload stored as JSONB.
     * Stored as a String here; the API layer serialises/deserialises as needed.
     */
    @Column(name = "data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String data;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    /**
     * The wall-clock time the event was consumed and written by this service.
     * Distinct from the event's own timestamp field which stays in {@code data}.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Original event timestamp sent by the producer. */
    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
