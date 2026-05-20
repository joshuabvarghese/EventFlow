package com.platform.query.controller;

import com.platform.query.dto.QueryDto;
import com.platform.query.service.EventQueryService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-side REST API for EventFlow.
 *
 * Base path: {@code /api/v1/query}
 *
 * <pre>
 * GET /api/v1/query/events               — filtered, paginated event list
 * GET /api/v1/query/events/{eventId}     — single event by business ID
 * GET /api/v1/query/events/types         — distinct event types
 * GET /api/v1/query/stats                — aggregate stats (dashboard)
 * GET /api/v1/query/health               — liveness probe
 * </pre>
 *
 * All endpoints are read-only (GET + OPTIONS only — enforced by CorsConfig).
 */
@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
@Slf4j
public class EventQueryController {

    private final EventQueryService queryService;

    // ── Event list ────────────────────────────────────────────────────────────

    /**
     * Returns a paginated, filtered list of stored events.
     *
     * <p>Example:
     * <pre>
     * GET /api/v1/query/events?eventType=user.login&from=2025-01-01T00:00:00Z&page=0&size=50
     * </pre>
     *
     * @param eventType filter by exact event type (optional)
     * @param userId    filter by user ID (optional)
     * @param status    filter by status: {@code processed | failed | duplicate} (optional)
     * @param from      lower bound on {@code created_at}, ISO-8601 (optional)
     * @param to        upper bound on {@code created_at}, ISO-8601 (optional)
     * @param page      zero-based page number (default 0)
     * @param size      page size, 1–200 (default 50)
     */
    @GetMapping("/events")
    @Timed(value = "query.api.events.list", description = "Filtered event list latency")
    public ResponseEntity<QueryDto.PagedEvents> listEvents(
            @RequestParam(required = false) String  eventType,
            @RequestParam(required = false) String  userId,
            @RequestParam(required = false) String  status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        log.debug("Query events: type={} userId={} status={} from={} to={} page={} size={}",
                eventType, userId, status, from, to, page, size);

        QueryDto.EventFilter filter = new QueryDto.EventFilter(
                eventType, userId, status, from, to, page, size);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(queryService.query(filter));
    }

    // ── Single event ──────────────────────────────────────────────────────────

    /**
     * Looks up a single event by its business-level {@code eventId}.
     * Returns 404 if no event with that ID has been stored.
     */
    @GetMapping("/events/{eventId}")
    @Timed(value = "query.api.events.single", description = "Single event lookup latency")
    public ResponseEntity<QueryDto.EventResponse> getEvent(
            @PathVariable String eventId) {

        return queryService.findByEventId(eventId)
                .map(event -> ResponseEntity.ok()
                        .header("Cache-Control", "no-cache")
                        .<QueryDto.EventResponse>body(event))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Event types ───────────────────────────────────────────────────────────

    /**
     * Returns all distinct event types present in the store.
     * Useful for populating filter dropdowns in the dashboard.
     */
    @GetMapping("/events/types")
    public ResponseEntity<List<String>> eventTypes() {
        return ResponseEntity.ok(queryService.eventTypes());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Returns an aggregate statistics snapshot suitable for the dashboard.
     *
     * <p>Includes:
     * <ul>
     *   <li>Total stored / processed / failed / duplicate counts</li>
     *   <li>Success rate (0–100)</li>
     *   <li>Per-type breakdown</li>
     *   <li>Per-status breakdown</li>
     *   <li>Hourly time-series for the last 24 h</li>
     *   <li>20 most recent events for the live log panel</li>
     * </ul>
     */
    @GetMapping("/stats")
    @Timed(value = "query.api.stats", description = "Stats aggregation latency")
    public ResponseEntity<QueryDto.StatsResponse> stats() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(queryService.stats());
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "event-query-service"
        ));
    }
}
