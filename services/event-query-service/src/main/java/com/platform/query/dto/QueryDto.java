package com.platform.query.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platform.query.model.StoredEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * All DTOs for the query service API, kept in one file for readability.
 * Each is a Java record — immutable, compact, auto-generates equals/hashCode.
 */
public final class QueryDto {

    private QueryDto() {}

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Single event response — safe subset of StoredEvent with raw JSONB
     * strings replaced by Object so Jackson serialises them as proper JSON.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventResponse(
            String  id,
            String  eventId,
            String  eventType,
            String  userId,
            String  source,
            String  version,
            String  correlationId,
            String  sourceTopic,
            String  status,
            Object  data,
            Object  metadata,
            Instant eventTimestamp,
            Instant createdAt
    ) {
        public static EventResponse from(StoredEvent e) {
            return new EventResponse(
                    e.getId()            != null ? e.getId().toString() : null,
                    e.getEventId(),
                    e.getEventType(),
                    e.getUserId(),
                    e.getSource(),
                    e.getVersion(),
                    e.getCorrelationId(),
                    e.getSourceTopic(),
                    e.getStatus(),
                    e.getData(),      // raw JSON string; Jackson re-serialises inline
                    e.getMetadata(),
                    e.getEventTimestamp(),
                    e.getCreatedAt()
            );
        }
    }

    /** Paginated list of events with pagination metadata. */
    public record PagedEvents(
            List<EventResponse> events,
            long   totalElements,
            int    totalPages,
            int    page,
            int    size,
            boolean first,
            boolean last
    ) {}

    /** Aggregate statistics snapshot from the database. */
    public record StatsResponse(
            long                    totalStored,
            long                    totalProcessed,
            long                    totalFailed,
            long                    totalDuplicate,
            double                  successRate,
            Map<String, Long>       eventsByType,
            Map<String, Long>       eventsByStatus,
            List<HourlyPoint>       last24hTimeSeries,
            List<RecentEvent>       recentEvents,
            Instant                 asOf
    ) {}

    /** A single bucket in the hourly time-series chart. */
    public record HourlyPoint(
            String  hour,       // ISO-8601 string for easy charting
            long    count
    ) {}

    /** Compact recent-event entry for the live log panel. */
    public record RecentEvent(
            String  eventId,
            String  eventType,
            String  userId,
            String  status,
            String  sourceTopic,
            Instant createdAt
    ) {
        public static RecentEvent from(StoredEvent e) {
            return new RecentEvent(
                    e.getEventId(),
                    e.getEventType(),
                    e.getUserId(),
                    e.getStatus(),
                    e.getSourceTopic(),
                    e.getCreatedAt()
            );
        }
    }

    // ── Inbound query params ──────────────────────────────────────────────────

    /**
     * Query parameters for {@code GET /api/v1/query/events}.
     * All fields optional; null means "no filter on this dimension".
     */
    public record EventFilter(
            String  eventType,
            String  userId,
            String  status,
            Instant from,
            Instant to,
            int     page,
            int     size
    ) {
        /** Clamp page size to safe bounds. */
        public EventFilter {
            if (page < 0)   page = 0;
            if (size < 1)   size = 50;
            if (size > 200) size = 200;
        }
    }
}
