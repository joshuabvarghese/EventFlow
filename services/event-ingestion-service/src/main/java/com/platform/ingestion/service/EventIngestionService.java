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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core event ingestion service.
 *
 * Pipeline: deduplication → topic routing → Kafka publish → metrics update.
 *
 * Design decisions:
 *  - ingest() returns boolean and never throws. Failures go to DLQ; the caller
 *    (controller / batch loop) always gets a result and can continue.
 *  - All AtomicLong counters are injected from MetricsConfig so the same
 *    instances back both the JSON stats API and the Prometheus Gauges.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventIngestionService {

    private final EventProducer                  eventProducer;
    private final RedisTemplate<String, String>  redisTemplate;

    // Shared with MetricsConfig Gauge beans
    private final AtomicLong totalEventsReceived;
    private final AtomicLong totalEventsProcessed;
    private final AtomicLong totalEventsFailed;
    private final AtomicLong totalEventsDuplicated;

    // Prometheus instruments
    private final Counter eventsIngestedCounter;
    private final Counter eventsFailedCounter;
    private final Counter eventsDuplicatesCounter;
    private final Timer   ingestionTimer;

    // Per-type breakdown for the stats API
    private final Map<String, AtomicLong> eventTypeCounters = new ConcurrentHashMap<>();

    private final Instant startTime = Instant.now();

    private static final String   DEDUP_PREFIX = "eventflow:dedup:";
    private static final Duration DEDUP_TTL    = Duration.ofHours(24);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ingests a single event.
     *
     * @return true  — event accepted and published to Kafka
     *         false — duplicate (skipped) or Kafka error (sent to DLQ)
     */
    public boolean ingest(Event event) {
        totalEventsReceived.incrementAndGet();

        Boolean result = ingestionTimer.record(() -> {
            try {
                if (isDuplicate(event)) {
                    log.info("Duplicate skipped: eventId={}", event.eventId());
                    totalEventsDuplicated.incrementAndGet();
                    eventsDuplicatesCounter.increment();
                    return Boolean.FALSE;
                }

                markAsSeen(event);

                String topic = topicFor(event);
                eventProducer.send(topic, event.userId(), event);

                totalEventsProcessed.incrementAndGet();
                eventsIngestedCounter.increment();
                eventTypeCounters
                        .computeIfAbsent(event.eventType(), k -> new AtomicLong(0))
                        .incrementAndGet();

                log.debug("Ingested: eventId={}, type={}, topic={}",
                        event.eventId(), event.eventType(), topic);
                return Boolean.TRUE;

            } catch (Exception e) {
                totalEventsFailed.incrementAndGet();
                eventsFailedCounter.increment();
                log.error("Ingestion failed: eventId={}", event.eventId(), e);
                sendToDlq(event, e);
                return Boolean.FALSE;
            }
        });

        return Boolean.TRUE.equals(result);
    }

    /**
     * Ingests a batch of events. Each event is processed independently;
     * a failure on one never aborts the rest.
     */
    public BatchResult ingestBatch(List<Event> events) {
        int successCount = 0;
        int failureCount = 0;
        List<String> failedIds = new ArrayList<>();

        for (Event event : events) {
            if (ingest(event)) {
                successCount++;
            } else {
                failureCount++;
                failedIds.add(event.eventId());
            }
        }

        return new BatchResult(successCount, failureCount, failedIds);
    }

    /**
     * Returns a stats snapshot matching the React dashboard's EventStats interface:
     * <pre>
     * {
     *   totalReceived:   number,
     *   totalProcessed:  number,
     *   totalFailed:     number,
     *   totalDuplicated: number,
     *   successRate:     number,   // 0–100
     *   eventsByType:    Record&lt;string, number&gt;,
     *   uptimeSeconds:   number
     * }
     * </pre>
     */
    public Map<String, Object> getStatistics() {
        // Build eventsByType first — ConcurrentHashMap is safe to iterate
        Map<String, Long> byType = new HashMap<>();
        eventTypeCounters.forEach((k, v) -> byType.put(k, v.get()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalReceived",   totalEventsReceived.get());
        stats.put("totalProcessed",  totalEventsProcessed.get());
        stats.put("totalFailed",     totalEventsFailed.get());
        stats.put("totalDuplicated", totalEventsDuplicated.get());
        stats.put("successRate",     successRate());
        stats.put("eventsByType",    byType);
        stats.put("uptimeSeconds",   Duration.between(startTime, Instant.now()).getSeconds());
        return stats;
    }

    /** Health probe used by the /health endpoint. */
    public Map<String, Object> healthCheck() {
        boolean kafkaOk;
        boolean redisOk;
        try {
            kafkaOk = eventProducer.isHealthy();
        } catch (Exception e) {
            kafkaOk = false;
        }
        try {
            redisTemplate.opsForValue().get("eventflow:health");
            redisOk = true;
        } catch (Exception e) {
            redisOk = false;
        }

        String status = (kafkaOk && redisOk) ? "UP" : "DOWN";
        Map<String, Object> health = new HashMap<>();
        health.put("status",          status);
        health.put("kafka",           kafkaOk ? "UP" : "DOWN");
        health.put("redis",           redisOk ? "UP" : "DOWN");
        health.put("eventsProcessed", totalEventsProcessed.get());
        health.put("uptime",          Duration.between(startTime, Instant.now()).toString());
        return health;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isDuplicate(Event event) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(DEDUP_PREFIX + event.eventId()));
    }

    private void markAsSeen(Event event) {
        redisTemplate.opsForValue()
                .set(DEDUP_PREFIX + event.eventId(), "1", DEDUP_TTL.toMinutes(), TimeUnit.MINUTES);
    }

    private String topicFor(Event event) {
        if (event.isCritical()) return "events.critical";
        return switch (event.getCategory()) {
            case "user"        -> "events.user";
            case "transaction" -> "events.transaction";
            case "analytics"   -> "events.analytics";
            case "system"      -> "events.system";
            default            -> "events.raw";
        };
    }

    private void sendToDlq(Event event, Exception cause) {
        try {
            eventProducer.send("events.dlq", event.eventId(), event);
            log.info("Event sent to DLQ: eventId={}, reason={}", event.eventId(), cause.getMessage());
        } catch (Exception e) {
            log.error("DLQ send also failed: eventId={}", event.eventId(), e);
        }
    }

    private double successRate() {
        long received = totalEventsReceived.get();
        if (received == 0) return 100.0;
        return Math.round((totalEventsProcessed.get() * 10000.0) / received) / 100.0;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public record BatchResult(int successCount, int failureCount, List<String> failedEventIds) {}

    /** Kept for GlobalExceptionHandler reference — no longer thrown by ingest(). */
    public static class EventIngestionException extends RuntimeException {
        public EventIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
