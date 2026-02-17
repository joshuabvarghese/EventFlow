package com.platform.ingestion.service;

import com.platform.ingestion.kafka.EventProducer;
import com.platform.ingestion.model.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for ingesting events into the platform.
 * Handles validation, deduplication, and routing to Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventIngestionService {

    private final EventProducer eventProducer;
    private final RedisTemplate<String, String> redisTemplate;

    // In-memory counters for statistics (in production, use proper metrics store)
    private final AtomicLong totalEventsReceived = new AtomicLong(0);
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalEventsFailed = new AtomicLong(0);
    private final Map<String, AtomicLong> eventTypeCounters = new ConcurrentHashMap<>();

    private static final String DEDUP_KEY_PREFIX = "event:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    /**
     * Ingest a single event
     */
    public void ingest(Event event) {
        totalEventsReceived.incrementAndGet();

        try {
            // Check for duplicates using Redis
            if (isDuplicate(event)) {
                log.info("Duplicate event detected and skipped: eventId={}", event.eventId());
                return;
            }

            // Mark event as seen
            markAsSeen(event);

            // Route to appropriate Kafka topic based on event type
            String topic = determineTopicForEvent(event);
            
            // Publish to Kafka
            eventProducer.send(topic, event.eventId(), event);

            // Update statistics
            totalEventsProcessed.incrementAndGet();
            eventTypeCounters.computeIfAbsent(event.eventType(), k -> new AtomicLong(0))
                             .incrementAndGet();

            log.debug("Event ingested successfully: eventId={}, type={}, topic={}", 
                     event.eventId(), event.eventType(), topic);

        } catch (Exception e) {
            totalEventsFailed.incrementAndGet();
            log.error("Failed to ingest event: eventId={}", event.eventId(), e);
            
            // Send to dead letter queue
            sendToDeadLetterQueue(event, e);
            throw new EventIngestionException("Failed to process event", e);
        }
    }

    /**
     * Ingest multiple events in batch
     */
    public BatchResult ingestBatch(List<Event> events) {
        int successCount = 0;
        int failureCount = 0;
        List<String> failedEventIds = new ArrayList<>();

        for (Event event : events) {
            try {
                ingest(event);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                failedEventIds.add(event.eventId());
                log.error("Failed to ingest event in batch: eventId={}", event.eventId(), e);
            }
        }

        return new BatchResult(successCount, failureCount, failedEventIds);
    }

    /**
     * Check if event is a duplicate
     */
    private boolean isDuplicate(Event event) {
        String dedupKey = DEDUP_KEY_PREFIX + event.eventId();
        Boolean exists = redisTemplate.hasKey(dedupKey);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Mark event as seen for deduplication
     */
    private void markAsSeen(Event event) {
        String dedupKey = DEDUP_KEY_PREFIX + event.eventId();
        redisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL.toMinutes(), TimeUnit.MINUTES);
    }

    /**
     * Determine which Kafka topic to send the event to based on event type
     */
    private String determineTopicForEvent(Event event) {
        // Route critical events to high-priority topic
        if (event.isCritical()) {
            return "events.critical";
        }

        // Route by category
        return switch (event.getCategory()) {
            case "user" -> "events.user";
            case "transaction" -> "events.transaction";
            case "analytics" -> "events.analytics";
            case "system" -> "events.system";
            default -> "events.raw";
        };
    }

    /**
     * Send failed events to dead letter queue for later analysis
     */
    private void sendToDeadLetterQueue(Event event, Exception error) {
        try {
            eventProducer.send("events.dlq", event.eventId(), event);
            log.info("Event sent to DLQ: eventId={}, error={}", event.eventId(), error.getMessage());
        } catch (Exception e) {
            log.error("Failed to send event to DLQ: eventId={}", event.eventId(), e);
        }
    }

    /**
     * Get ingestion statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "totalReceived", totalEventsReceived.get(),
                "totalProcessed", totalEventsProcessed.get(),
                "totalFailed", totalEventsFailed.get(),
                "successRate", calculateSuccessRate(),
                "eventsByType", getEventTypeStats()
        );
    }

    /**
     * Calculate success rate
     */
    private double calculateSuccessRate() {
        long total = totalEventsReceived.get();
        if (total == 0) return 100.0;
        return (totalEventsProcessed.get() * 100.0) / total;
    }

    /**
     * Get per-event-type statistics
     */
    private Map<String, Long> getEventTypeStats() {
        return eventTypeCounters.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get()
                ));
    }

    /**
     * Health check
     */
    public Map<String, Object> healthCheck() {
        try {
            // Check Kafka connectivity
            boolean kafkaHealthy = eventProducer.isHealthy();
            
            // Check Redis connectivity
            boolean redisHealthy = checkRedisHealth();

            String status = (kafkaHealthy && redisHealthy) ? "UP" : "DOWN";

            return Map.of(
                    "status", status,
                    "kafka", kafkaHealthy ? "UP" : "DOWN",
                    "redis", redisHealthy ? "UP" : "DOWN",
                    "eventsProcessed", totalEventsProcessed.get()
            );
        } catch (Exception e) {
            return Map.of(
                    "status", "DOWN",
                    "error", e.getMessage()
            );
        }
    }

    /**
     * Check Redis connectivity
     */
    private boolean checkRedisHealth() {
        try {
            redisTemplate.opsForValue().get("health:check");
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }

    /**
     * Batch processing result
     */
    public record BatchResult(
            int successCount,
            int failureCount,
            List<String> failedEventIds
    ) {}

    /**
     * Custom exception for event ingestion failures
     */
    public static class EventIngestionException extends RuntimeException {
        public EventIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
