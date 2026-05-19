package com.platform.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates an Event against format rules and business constraints.
 */
@Component
@Slf4j
public class EventValidator {

    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*)+$");

    private static final Pattern USER_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_@.\\-]{1,128}$");

    private static final int  MAX_EVENT_SIZE_BYTES      = 1_048_576; // 1 MB
    private static final long MAX_TIMESTAMP_DRIFT_HOURS = 24;

    private static final Set<String> BUSINESS_RULE_TYPES = Set.of(
            "user.signup", "user.login", "user.logout", "user.profile.updated",
            "transaction.created", "transaction.completed", "transaction.failed",
            "analytics.page.view", "analytics.page_view", "analytics.button.click",
            "analytics.form.submit", "system.error", "system.warning",
            "fraud.detected", "security.breach.attempted"
    );

    public ValidationResult validate(Event event) {
        List<String> errors = new ArrayList<>();

        validateEventId(event, errors);
        validateEventType(event, errors);
        validateUserId(event, errors);
        validateTimestamp(event, errors);
        validateDataPayload(event, errors);
        validateEventSize(event, errors);

        if (errors.isEmpty()) {
            validateBusinessRules(event, errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    // ── Field validators ──────────────────────────────────────────────────────

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
            errors.add("eventType must be lowercase dot-separated (e.g. user.login)");
        }
    }

    private void validateUserId(Event event, List<String> errors) {
        if (event.userId() == null || event.userId().isBlank()) {
            errors.add("userId is required");
            return;
        }
        if (!USER_ID_PATTERN.matcher(event.userId()).matches()) {
            errors.add("userId contains invalid characters");
        }
    }

    private void validateTimestamp(Event event, List<String> errors) {
        if (event.timestamp() == null) {
            errors.add("timestamp is required");
            return;
        }
        long drift = Math.abs(ChronoUnit.HOURS.between(event.timestamp(), Instant.now()));
        if (drift > MAX_TIMESTAMP_DRIFT_HOURS) {
            errors.add(String.format("timestamp drift %dh exceeds maximum %dh",
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
                errors.add(String.format("event size %d bytes exceeds 1 MB limit", size));
            }
        } catch (Exception e) {
            log.warn("Could not estimate event size for eventId={}", event.eventId());
        }
    }

    // ── Business rules ────────────────────────────────────────────────────────

    private void validateBusinessRules(Event event, List<String> errors) {
        if (!BUSINESS_RULE_TYPES.contains(event.eventType())) {
            return; // unknown-but-valid type → routed to events.raw, no business rules
        }
        switch (event.eventType()) {
            case "user.signup"                              -> requireFields(event, errors, "email", "source");
            case "transaction.created",
                 "transaction.completed",
                 "transaction.failed"                      -> requireFields(event, errors, "amount", "currency");
            case "fraud.detected"                          -> requireFields(event, errors, "riskScore", "reason");
            case "security.breach.attempted"               -> requireFields(event, errors, "ip", "reason");
            default                                        -> { /* no additional rules */ }
        }
    }

    private void requireFields(Event event, List<String> errors, String... fields) {
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

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * Validation outcome.
     *
     * isValid() and getErrors() are written explicitly rather than using @Getter.
     * Lombok's @Getter on a primitive boolean field named "valid" generates
     * "isValid()" per the JavaBean spec, but some compiler + annotation processor
     * combinations on Java 21 generate "getValid()" instead, causing
     * "cannot find symbol" in callers. Explicit methods are unambiguous.
     */
    public static final class ValidationResult {

        private final boolean      valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid  = valid;
            this.errors = List.copyOf(errors); // defensive copy
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
