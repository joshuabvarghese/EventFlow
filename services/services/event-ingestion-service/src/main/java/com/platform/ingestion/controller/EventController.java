package com.platform.ingestion.controller;

import com.platform.ingestion.model.Event;
import com.platform.ingestion.service.EventIngestionService;
import com.platform.ingestion.validation.EventValidator;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for event ingestion.
 * Supports both synchronous and asynchronous (reactive) endpoints.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventIngestionService ingestionService;
    private final EventValidator eventValidator;
    private final MeterRegistry meterRegistry;

    /**
     * Ingest a single event (synchronous)
     */
    @PostMapping
    @Timed(value = "event.ingestion.single", description = "Time taken to ingest a single event")
    public ResponseEntity<Map<String, String>> ingestEvent(@Valid @RequestBody Event event) {
        log.info("Received event: type={}, userId={}, eventId={}", 
                 event.eventType(), event.userId(), event.eventId());

        // Validate event
        var validationResult = eventValidator.validate(event);
        if (!validationResult.isValid()) {
            log.warn("Event validation failed: eventId={}, errors={}", 
                     event.eventId(), validationResult.getErrors());
            incrementCounter("events.validation.failed", event.eventType());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Validation failed", 
                                 "details", String.join(", ", validationResult.getErrors())));
        }

        // Ingest event
        try {
            ingestionService.ingest(event);
            incrementCounter("events.ingested.success", event.eventType());
            
            return ResponseEntity.accepted()
                    .body(Map.of(
                            "eventId", event.eventId(),
                            "status", "accepted",
                            "message", "Event queued for processing"
                    ));
        } catch (Exception e) {
            log.error("Failed to ingest event: eventId={}", event.eventId(), e);
            incrementCounter("events.ingested.failed", event.eventType());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error", "message", e.getMessage()));
        }
    }

    /**
     * Batch ingest multiple events (synchronous)
     */
    @PostMapping("/batch")
    @Timed(value = "event.ingestion.batch", description = "Time taken to ingest batch of events")
    public ResponseEntity<Map<String, Object>> ingestBatch(@RequestBody List<@Valid Event> events) {
        log.info("Received batch of {} events", events.size());

        if (events.size() > 1000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Batch too large", "maxSize", 1000));
        }

        try {
            var results = ingestionService.ingestBatch(events);
            
            return ResponseEntity.accepted()
                    .body(Map.of(
                            "totalEvents", events.size(),
                            "successCount", results.successCount(),
                            "failureCount", results.failureCount(),
                            "status", "accepted"
                    ));
        } catch (Exception e) {
            log.error("Failed to ingest batch", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error", "message", e.getMessage()));
        }
    }

    /**
     * Reactive endpoint for streaming events (asynchronous)
     */
    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> ingestStream(
            @RequestBody Flux<Event> eventStream) {
        
        return eventStream
                .flatMap(event -> {
                    var validationResult = eventValidator.validate(event);
                    if (validationResult.isValid()) {
                        return Mono.fromCallable(() -> {
                            ingestionService.ingest(event);
                            return event;
                        });
                    } else {
                        log.warn("Skipping invalid event: {}", event.eventId());
                        return Mono.empty();
                    }
                })
                .count()
                .map(count -> ResponseEntity.accepted()
                        .body(Map.of("processedEvents", count, "status", "completed")));
    }

    /**
     * Server-Sent Events endpoint for real-time event statistics
     */
    @GetMapping(value = "/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamStats() {
        return Flux.interval(Duration.ofSeconds(5))
                .map(seq -> {
                    var stats = ingestionService.getStatistics();
                    return ServerSentEvent.<Map<String, Object>>builder()
                            .id(String.valueOf(seq))
                            .event("stats")
                            .data(stats)
                            .build();
                });
    }

    /**
     * Health check endpoint for event ingestion
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var health = ingestionService.healthCheck();
        var status = health.get("status").equals("UP") ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }

    /**
     * Get current statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(ingestionService.getStatistics());
    }

    /**
     * Helper method to increment Prometheus counters
     */
    private void incrementCounter(String counterName, String eventType) {
        Counter.builder(counterName)
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }
}
