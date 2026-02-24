package com.platform.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.model.Event;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Event validation against format rules and business constraints.
 *
 * Design decisions:
 *  - Event type whitelist is intentionally open (category.* wildcard) to avoid
 *    having to deploy a backend change every time a new event type is introduced
 *    in the frontend or other producers. The pattern enforces the dot-separated
 *    lowercase format; unknown-but-valid types route to events.raw.
 *  - Timestamp drift of ±24 h is checked to reject obviously stale or future-
 *    dated events while tolerating clock skew between services.
 *  - Business-rule validations (transaction must have amount, etc.) are applied
 *    only for known event types so unrecognised types aren't silently rejected.
 */
@Component
@Slf4j
public class EventValidator {

    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*)+$");

    private static final Pattern USER_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_@.-]{1,128}$");

    private static final int    MAX_EVENT_SIZE_BYTES      = 1_048_576; // 1 MB
    private static final long   MAX_TIMESTAMP_DRIFT_HOURS = 24;

    /**
     * Known event types for which business-rule validation is applied.
     * Unrecognised types that match the format pattern are accepted and
     * routed to events.raw — avoids a deployment for every new event type.
     */
    private static final Set<String> BUSINESS_RULE_TYPES = Set.of(
            "user.signup",
            "user.login",
            "user.logout",
            "user.profile.updated",
            "transaction.created",
            "transaction.completed",
            "transaction.failed",
            "analytics.page.view",
            "analytics.page_view",
            "analytics.button.click",
            "analytics.form.submit",
            "system.error",
            "system.warning",
            "fraud.detected",
            "security.breach.attempted"
    );

    public ValidationResult validate(Event event) {
        List<String> errors = new ArrayList<>();

        validateEventId(event, errors);
        validateEventType(event, errors);
        validateUserId(event, errors);
        validateTimestamp(event, errors);
        validateDataPayload(event, errors);
        validateEventSize(event, errors);

        // Only run business rules when the event type itself is valid
        if (errors.isEmpty()) {
            validateBusinessRules(event, errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    // ── Field validators ─────────────────────────────────────────────────────

    private void validateEventId(Event event, List<String> errors) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            errors.add("eventId is required");
        }
    }

    private void validateEventType(Event event, List<String> errors) {
        if (event.eventType() == null || event.eventType().isBlank()) {
            errors.add("eventType is required");
            return;
        }
        if (!EVENT_TYPE_PATTERN.matcher(event.eventType()).matches()) {
            errors.add("eventType must be lowercase dot-separated (e.g. user.login, analytics.page.view)");
        }
    }

    private void validateUserId(Event event, List<String> errors) {
        if (event.userId() == null || event.userId().isBlank()) {
            errors.add("userId is required");
            return;
        }
        if (!USER_ID_PATTERN.matcher(event.userId()).matches()) {
            errors.add("userId contains invalid characters (allowed: a-z A-Z 0-9 _ @ . -)");
        }
    }

    private void validateTimestamp(Event event, List<String> errors) {
        if (event.timestamp() == null) {
            errors.add("timestamp is required");
            return;
        }
        long drift = Math.abs(ChronoUnit.HOURS.between(event.timestamp(), Instant.now()));
        if (drift > MAX_TIMESTAMP_DRIFT_HOURS) {
            errors.add(String.format(
                    "timestamp drift is %d hours — maximum allowed is %d hours",
                    drift, MAX_TIMESTAMP_DRIFT_HOURS));
        }
    }

    private void validateDataPayload(Event event, List<String> errors) {
        JsonNode data = event.data();
        if (data != null && !data.isNull() && !data.isObject()) {
            errors.add("data must be a JSON object");
        }
    }

    private void validateEventSize(Event event, List<String> errors) {
        try {
            int size = event.toString().getBytes().length;
            if (size > MAX_EVENT_SIZE_BYTES) {
                errors.add(String.format(
                        "event size %d bytes exceeds maximum %d bytes (1 MB)",
                        size, MAX_EVENT_SIZE_BYTES));
            }
        } catch (Exception e) {
            log.warn("Unable to estimate event size for eventId={}", event.eventId());
        }
    }

    // ── Business rules ───────────────────────────────────────────────────────

    private void validateBusinessRules(Event event, List<String> errors) {
        if (!BUSINESS_RULE_TYPES.contains(event.eventType())) {
            // Unknown-but-valid type — skip business rules, route to events.raw
            return;
        }
        switch (event.eventType()) {
            case "user.signup"           -> requireDataFields(event, errors, "email", "source");
            case "transaction.created",
                 "transaction.completed",
                 "transaction.failed"   -> requireDataFields(event, errors, "amount", "currency");
            case "fraud.detected"        -> requireDataFields(event, errors, "riskScore", "reason");
            case "security.breach.attempted" -> requireDataFields(event, errors, "ip", "reason");
        }
    }

    private void requireDataFields(Event event, List<String> errors, String... fields) {
        JsonNode data = event.data();
        if (data == null || data.isNull()) {
            errors.add(event.eventType() + " requires a data payload");
            return;
        }
        for (String field : fields) {
            if (!data.has(field)) {
                errors.add(event.eventType() + " requires data." + field);
            }
        }
    }

    // ── Result type ──────────────────────────────────────────────────────────

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid  = valid;
            this.errors = errors;
        }
    }
}
