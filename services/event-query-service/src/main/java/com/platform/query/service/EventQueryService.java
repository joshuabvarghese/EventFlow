package com.platform.query.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.query.dto.QueryDto;
import com.platform.query.model.StoredEvent;
import com.platform.query.repository.EventRepository;
import com.platform.query.repository.EventRepository.EventTypeCount;
import com.platform.query.repository.EventRepository.HourlyCount;
import com.platform.query.repository.EventRepository.StatusCount;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventQueryService {

    private final EventRepository repository;
    private final ObjectMapper     objectMapper;
    private final MeterRegistry    meterRegistry;

    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

    // ── Write path (called by Kafka consumer) ─────────────────────────────────

    /**
     * Persists a raw Kafka message payload as a {@link StoredEvent}.
     * <p>
     * Duplicate {@code eventId} values are silently ignored — the ingestion
     * service already deduplicates via Redis, but the Kafka consumer may
     * replay messages during a rebalance.
     *
     * @param payload     deserialized message body (Map<String, Object>)
     * @param sourceTopic the Kafka topic the message arrived on
     */
    @Transactional
    public void store(Map<String, Object> payload, String sourceTopic) {
        String eventId = extractString(payload, "eventId");
        if (eventId == null || eventId.isBlank()) {
            log.warn("Dropping message with no eventId from topic={}", sourceTopic);
            return;
        }

        // Idempotency guard — silently skip replays
        if (repository.existsByEventId(eventId)) {
            log.debug("Skipping duplicate eventId={}", eventId);
            counter("query.events.duplicate").increment();
            return;
        }

        String status = "failed".equals(sourceTopic) || sourceTopic.contains("dlq")
                ? "failed" : "processed";

        StoredEvent event = StoredEvent.builder()
                .eventId(eventId)
                .eventType(extractString(payload, "eventType"))
                .userId(extractString(payload, "userId"))
                .source(extractString(payload, "source"))
                .version(extractString(payload, "version"))
                .correlationId(extractString(payload, "correlationId"))
                .sourceTopic(sourceTopic)
                .status(status)
                .eventTimestamp(extractInstant(payload, "timestamp"))
                .data(toJson(payload.get("data")))
                .metadata(toJson(payload.get("metadata")))
                .build();

        try {
            repository.save(event);
            counter("query.events.stored").increment();
            log.debug("Stored eventId={} type={} topic={}", eventId, event.getEventType(), sourceTopic);
        } catch (DataIntegrityViolationException ex) {
            // Race between two consumer threads — safe to ignore
            log.debug("Concurrent duplicate eventId={}", eventId);
        }
    }

    // ── Read path ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<QueryDto.EventResponse> findByEventId(String eventId) {
        return repository.findByEventId(eventId)
                .map(QueryDto.EventResponse::from);
    }

    @Transactional(readOnly = true)
    public QueryDto.PagedEvents query(QueryDto.EventFilter filter) {
        Pageable pageable = PageRequest.of(filter.page(), filter.size());
        Page<StoredEvent> page = repository.findFiltered(
                filter.eventType(),
                filter.userId(),
                filter.status(),
                filter.from(),
                filter.to(),
                pageable
        );

        List<QueryDto.EventResponse> events = page.getContent()
                .stream()
                .map(QueryDto.EventResponse::from)
                .toList();

        return new QueryDto.PagedEvents(
                events,
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                page.isFirst(),
                page.isLast()
        );
    }

    @Transactional(readOnly = true)
    public List<String> eventTypes() {
        return repository.findDistinctEventTypes();
    }

    @Transactional(readOnly = true)
    public QueryDto.StatsResponse stats() {
        // Count by type
        Map<String, Long> byType = new LinkedHashMap<>();
        for (EventTypeCount row : repository.countByEventType()) {
            byType.put(row.getEventType(), row.getCount());
        }

        // Count by status
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long totalProcessed = 0;
        long totalFailed    = 0;
        long totalDuplicate = 0;
        for (StatusCount row : repository.countByStatus()) {
            byStatus.put(row.getStatus(), row.getCount());
            switch (row.getStatus()) {
                case "processed" -> totalProcessed += row.getCount();
                case "failed"    -> totalFailed    += row.getCount();
                case "duplicate" -> totalDuplicate += row.getCount();
            }
        }
        long total       = totalProcessed + totalFailed + totalDuplicate;
        double successRate = total == 0 ? 100.0
                : Math.round(totalProcessed * 10000.0 / total) / 100.0;

        // Hourly time-series for past 24 h
        List<QueryDto.HourlyPoint> timeSeries = new ArrayList<>();
        for (HourlyCount row : repository.countByHour(24)) {
            timeSeries.add(new QueryDto.HourlyPoint(
                    HOUR_FMT.format(row.getHour()),
                    row.getCount()
            ));
        }

        // Recent events for live log panel
        List<QueryDto.RecentEvent> recent = repository
                .findRecent(PageRequest.of(0, 20))
                .stream()
                .map(QueryDto.RecentEvent::from)
                .toList();

        return new QueryDto.StatsResponse(
                total,
                totalProcessed,
                totalFailed,
                totalDuplicate,
                successRate,
                byType,
                byStatus,
                timeSeries,
                recent,
                Instant.now()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }

    private Instant extractInstant(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null)         return null;
        if (v instanceof String s && !s.isBlank()) {
            try { return Instant.parse(s); } catch (Exception ignored) {}
        }
        return null;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise field to JSON: {}", e.getMessage());
            return null;
        }
    }

    private Counter counter(String name) {
        return meterRegistry.counter(name);
    }
}
