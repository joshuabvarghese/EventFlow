package com.platform.ingestion.service;

import com.platform.ingestion.kafka.EventProducer;
import com.platform.ingestion.model.Event;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Core event ingestion service.
 *
 * Responsibilities:
 *   - Deduplication via Redis (24-hour TTL sliding window)
 *   - Topic routing by event category / criticality
 *   - Publishing to Kafka via EventProducer
 *   - Updating Prometheus counters registered in MetricsConfig
 *   - Providing stats shaped to match the React dashboard's EventStats interface
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventIngestionService {

    private final EventProducer eventProducer;
    private final RedisTemplate<String, String> redisTemplate;

    // Injected from MetricsConfig — shared AtomicLongs drive both in-memory
    // stats API and Prometheus Gauge metrics from a single source of truth.
    private final AtomicLong totalEventsReceived;
    private final AtomicLong totalEventsProcessed;
    private final AtomicLong totalEventsFailed;
    private final AtomicLong totalEventsDuplicated;

    // Prometheus counters / timers
    private final Counter eventsIngestedCounter;
    private final Counter eventsFailedCounter;
    private final Counter eventsDuplicatesCounter;
    private final Timer ingestionTimer;

    // Per-type breakdown — returned as eventsByType in the stats response
    private final Map<String, AtomicLong> eventTypeCounters = new ConcurrentHashMap<>();

    // Service start time for uptime calculation
    private final Instant startTime = Instant.now();

    private static final String DEDUP_KEY_PREFIX = "eventflow:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ingest a single event: dedup → route → publish → metrics.
     *
     * Failures are handled by sending to the DLQ and incrementing the failed
     * counter. The exception is NOT re-thrown — a single bad event must not
     * crash the batch loop or the HTTP request for the caller. Callers that
     * need to distinguish success from failure should check the return value.
     *
     * @return true if the event was successfully published to Kafka, false otherwise
     */
    public boolean ingest(Event event) {
        totalEventsReceived.incrementAndGet();

        return ingestionTimer.record(() -> {
            try {
                if (isDuplicate(event)) {
                    log.info("Duplicate event skipped: eventId={}", event.eventId());
                    totalEventsDuplicated.incrementAndGet();
                    eventsDuplicatesCounter.increment();
                    return false;
                }

                markAsSeen(event);

                String topic = determineTopicForEvent(event);
                eventProducer.send(topic, event.userId(), event);

                totalEventsProcessed.incrementAndGet();
                eventsIngestedCounter.increment();
                eventTypeCounters
                        .computeIfAbsent(event.eventType(), k -> new AtomicLong(0))
                        .incrementAndGet();

                log.debug("Event ingested: eventId={}, type={}, topic={}",
                        event.eventId(), event.eventType(), topic);
                return true;

            } catch (Exception e) {
                totalEventsFailed.incrementAndGet();
                eventsFailedCounter.increment();
                log.error("Failed to ingest event: eventId={}", event.eventId(), e);
                sendToDeadLetterQueue(event, e);
                // Do not re-throw — the event is in the DLQ; let the caller continue.
                return false;
            }
        });
    }

    /**
     * Ingest a batch, collecting per-event results.
     * Each event is processed independently — a failure on one does not
     * abort the rest. ingest() never throws, so the loop always completes.
     */
    public BatchResult ingestBatch(List<Event> events) {
        int successCount = 0;
        int failureCount = 0;
        List<String> failedEventIds = new ArrayList<>();

        for (Event event : events) {
            boolean ok = ingest(event);
            if (ok) {
                successCount++;
            } else {
                failureCount++;
                failedEventIds.add(event.eventId());
            }
        }

        return new BatchResult(successCount, failureCount, failedEventIds);
    }

    /**
     * Returns stats shaped to match the React dashboard's EventStats interface:
     * <pre>
     * {
     *   totalReceived:  number,
     *   totalProcessed: number,
     *   totalFailed:    number,
     *   totalDuplicated: number,
     *   successRate:    number,          // 0–100
     *   eventsByType:   Record<string, number>,
     *   uptimeSeconds:  number
     * }
     * </pre>
     *
     * All fields are always present (no nulls) so the frontend can read them
     * without null-guards. eventsByType is an empty map (not null) when no
     * events have been processed yet.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Long> byType = getEventTypeStats();
        return Map.of(
                "totalReceived",    totalEventsReceived.get(),
                "totalProcessed",   totalEventsProcessed.get(),
                "totalFailed",      totalEventsFailed.get(),
                "totalDuplicated",  totalEventsDuplicated.get(),
                "successRate",      calculateSuccessRate(),
                "eventsByType",     byType.isEmpty() ? Map.of() : byType,
                "uptimeSeconds",    Duration.between(startTime, Instant.now()).getSeconds()
        );
    }

    /**
     * Health check — used by both the custom /health endpoint and Spring Actuator.
     */
    public Map<String, Object> healthCheck() {
        try {
            boolean kafkaHealthy = eventProducer.isHealthy();
            boolean redisHealthy = checkRedisHealth();
            String status = (kafkaHealthy && redisHealthy) ? "UP" : "DOWN";

            return Map.of(
                    "status",          status,
                    "kafka",           kafkaHealthy ? "UP" : "DOWN",
                    "redis",           redisHealthy ? "UP" : "DOWN",
                    "eventsProcessed", totalEventsProcessed.get(),
                    "uptime",          Duration.between(startTime, Instant.now()).toString()
            );
        } catch (Exception e) {
            return Map.of("status", "DOWN", "error", e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isDuplicate(Event event) {
        String key = DEDUP_KEY_PREFIX + event.eventId();
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private void markAsSeen(Event event) {
        String key = DEDUP_KEY_PREFIX + event.eventId();
        redisTemplate.opsForValue().set(key, "1", DEDUP_TTL.toMinutes(), TimeUnit.MINUTES);
    }

    private String determineTopicForEvent(Event event) {
        if (event.isCritical()) {
            return "events.critical";
        }
        return switch (event.getCategory()) {
            case "user"        -> "events.user";
            case "transaction" -> "events.transaction";
            case "analytics"   -> "events.analytics";
            case "system"      -> "events.system";
            default            -> "events.raw";
        };
    }

    private void sendToDeadLetterQueue(Event event, Exception error) {
        try {
            eventProducer.send("events.dlq", event.eventId(), event);
            log.info("Event sent to DLQ: eventId={}, reason={}", event.eventId(), error.getMessage());
        } catch (Exception e) {
            log.error("Failed to send event to DLQ: eventId={}", event.eventId(), e);
        }
    }

    private double calculateSuccessRate() {
        long total = totalEventsReceived.get();
        if (total == 0) return 100.0;
        return Math.round((totalEventsProcessed.get() * 10000.0) / total) / 100.0;
    }

    private Map<String, Long> getEventTypeStats() {
        return eventTypeCounters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    private boolean checkRedisHealth() {
        try {
            redisTemplate.opsForValue().get("eventflow:health");
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public record BatchResult(int successCount, int failureCount, List<String> failedEventIds) {}

    public static class EventIngestionException extends RuntimeException {
        public EventIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
