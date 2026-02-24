package com.platform.ingestion.controller;

import com.platform.ingestion.model.Event;
import com.platform.ingestion.service.EventIngestionService;
import com.platform.ingestion.validation.EventValidator;
import io.micrometer.core.annotation.Timed;
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
import java.util.UUID;

/**
 * REST controller for event ingestion.
 *
 * Endpoint summary:
 *
 *   POST   /api/v1/events            — ingest a single event
 *   POST   /api/v1/events/batch      — ingest up to 1 000 events
 *   POST   /api/v1/events/stream     — reactive NDJSON stream ingestion
 *   GET    /api/v1/events/stats      — current statistics (polled by dashboard)
 *   GET    /api/v1/events/stats/stream — SSE stream of statistics (5 s interval)
 *   GET    /api/v1/events/health     — service health (Kafka + Redis)
 *
 * Error responses are handled uniformly by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventIngestionService ingestionService;
    private final EventValidator eventValidator;

    // ── Single event ──────────────────────────────────────────────────────────

    @PostMapping
    @Timed(value = "eventflow.api.ingest.single", description = "Single event ingestion latency")
    public ResponseEntity<Map<String, Object>> ingestEvent(@Valid @RequestBody Event event) {
        String correlationId = event.correlationId() != null
                ? event.correlationId()
                : UUID.randomUUID().toString();

        log.info("Ingest request: type={}, userId={}, correlationId={}",
                event.eventType(), event.userId(), correlationId);

        var validation = eventValidator.validate(event);
        if (!validation.isValid()) {
            log.warn("Validation failed: correlationId={}, errors={}", correlationId, validation.getErrors());
            return ResponseEntity.badRequest().body(Map.of(
                    "error",         "Validation failed",
                    "details",       validation.getErrors(),
                    "correlationId", correlationId
            ));
        }

        ingestionService.ingest(event);

        return ResponseEntity.accepted()
                .header("X-Correlation-Id", correlationId)
                .body(Map.of(
                        "eventId",       event.eventId(),
                        "status",        "accepted",
                        "message",       "Event queued for processing",
                        "correlationId", correlationId
                ));
    }

    // ── Batch ─────────────────────────────────────────────────────────────────

    @PostMapping("/batch")
    @Timed(value = "eventflow.api.ingest.batch", description = "Batch event ingestion latency")
    public ResponseEntity<Map<String, Object>> ingestBatch(@RequestBody List<@Valid Event> events) {
        log.info("Batch ingest request: count={}", events.size());

        int maxBatch = 1000;
        if (events.size() > maxBatch) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "Batch too large",
                    "maxSize", maxBatch,
                    "received", events.size()
            ));
        }

        var results = ingestionService.ingestBatch(events);

        return ResponseEntity.accepted().body(Map.of(
                "totalEvents",   events.size(),
                "successCount",  results.successCount(),
                "failureCount",  results.failureCount(),
                "failedEventIds", results.failedEventIds(),
                "status",        "accepted"
        ));
    }

    // ── Reactive stream ingestion (NDJSON) ────────────────────────────────────

    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> ingestStream(@RequestBody Flux<Event> eventStream) {
        return eventStream
                .filter(event -> eventValidator.validate(event).isValid())
                .flatMap(event -> Mono.fromRunnable(() -> ingestionService.ingest(event)))
                .count()
                .map(count -> ResponseEntity.accepted()
                        .<Map<String, Object>>body(Map.of(
                                "processedEvents", count,
                                "status",          "completed"
                        )));
    }

    // ── Statistics (REST poll) ────────────────────────────────────────────────

    /**
     * Returns the full stats map consumed by the React dashboard's polling loop.
     * Shape matches the TypeScript EventStats interface exactly.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(ingestionService.getStatistics());
    }

    // ── Statistics (SSE stream) ───────────────────────────────────────────────

    /**
     * Server-Sent Events endpoint. The dashboard can subscribe here to receive
     * live stats pushes every 2.5 s without polling.
     *
     * EventSource usage in the React app:
     *   const es = new EventSource('http://localhost:8081/api/v1/events/stats/stream');
     *   es.addEventListener('stats', e => setStats(JSON.parse(e.data)));
     */
    @GetMapping(value = "/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamStats() {
        return Flux.interval(Duration.ofMillis(2500))
                .map(seq -> ServerSentEvent.<Map<String, Object>>builder()
                        .id(String.valueOf(seq))
                        .event("stats")
                        .data(ingestionService.getStatistics())
                        .build());
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var health = ingestionService.healthCheck();
        HttpStatus status = "UP".equals(health.get("status"))
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }
}
