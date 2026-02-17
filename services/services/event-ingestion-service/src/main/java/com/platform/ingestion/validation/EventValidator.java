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
 * Validates events against business rules and schema constraints.
 * In production, this would integrate with JSON Schema validation libraries.
 */
@Component
@Slf4j
public class EventValidator {

    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^[a-z]+\\.[a-z.]+$");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_EVENT_SIZE_BYTES = 1_048_576; // 1 MB
    private static final long MAX_TIMESTAMP_DRIFT_HOURS = 24;

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "user.signup",
            "user.login",
            "user.logout",
            "user.profile.updated",
            "transaction.created",
            "transaction.completed",
            "transaction.failed",
            "analytics.page.view",
            "analytics.button.click",
            "analytics.form.submit",
            "system.error",
            "system.warning",
            "fraud.detected",
            "security.breach.attempted"
    );

    /**
     * Validate an event
     */
    public ValidationResult validate(Event event) {
        List<String> errors = new ArrayList<>();

        // Validate event ID
        if (event.eventId() == null || event.eventId().isBlank()) {
            errors.add("Event ID is required");
        }

        // Validate event type
        if (event.eventType() == null || event.eventType().isBlank()) {
            errors.add("Event type is required");
        } else if (!EVENT_TYPE_PATTERN.matcher(event.eventType()).matches()) {
            errors.add("Event type must follow pattern: category.subcategory (lowercase, dot-separated)");
        } else if (!ALLOWED_EVENT_TYPES.contains(event.eventType())) {
            errors.add("Event type '" + event.eventType() + "' is not recognized");
        }

        // Validate user ID
        if (event.userId() == null || event.userId().isBlank()) {
            errors.add("User ID is required");
        } else if (!USER_ID_PATTERN.matcher(event.userId()).matches()) {
            errors.add("User ID contains invalid characters");
        }

        // Validate timestamp
        if (event.timestamp() == null) {
            errors.add("Timestamp is required");
        } else {
            Instant now = Instant.now();
            long hoursDrift = Math.abs(ChronoUnit.HOURS.between(event.timestamp(), now));
            if (hoursDrift > MAX_TIMESTAMP_DRIFT_HOURS) {
                errors.add("Timestamp drift too large: " + hoursDrift + " hours (max: " + MAX_TIMESTAMP_DRIFT_HOURS + ")");
            }
        }

        // Validate data payload
        if (event.data() != null) {
            validateDataPayload(event.data(), errors);
        }

        // Validate overall event size
        validateEventSize(event, errors);

        // Validate business rules based on event type
        validateBusinessRules(event, errors);

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Validate data payload
     */
    private void validateDataPayload(JsonNode data, List<String> errors) {
        if (data.isNull()) {
            errors.add("Data payload cannot be null");
            return;
        }

        // Check for required fields based on event type
        // In production, this would use JSON Schema validation
        if (!data.isObject()) {
            errors.add("Data payload must be a JSON object");
        }
    }

    /**
     * Validate event size
     */
    private void validateEventSize(Event event, List<String> errors) {
        try {
            // Rough estimation - in production, serialize and check actual size
            int estimatedSize = event.toString().getBytes().length;
            if (estimatedSize > MAX_EVENT_SIZE_BYTES) {
                errors.add("Event size exceeds maximum: " + estimatedSize + " bytes (max: " + MAX_EVENT_SIZE_BYTES + ")");
            }
        } catch (Exception e) {
            log.warn("Failed to estimate event size", e);
        }
    }

    /**
     * Validate business rules specific to event types
     */
    private void validateBusinessRules(Event event, List<String> errors) {
        switch (event.eventType()) {
            case "user.signup":
                validateUserSignup(event, errors);
                break;
            case "transaction.created":
                validateTransaction(event, errors);
                break;
            case "fraud.detected":
                validateFraudEvent(event, errors);
                break;
            // Add more specific validations as needed
        }
    }

    /**
     * Validate user signup event
     */
    private void validateUserSignup(Event event, List<String> errors) {
        if (event.data() == null) {
            errors.add("User signup event must have data payload");
            return;
        }

        JsonNode data = event.data();
        if (!data.has("email")) {
            errors.add("User signup event must include email");
        }
        if (!data.has("source")) {
            errors.add("User signup event must include source");
        }
    }

    /**
     * Validate transaction event
     */
    private void validateTransaction(Event event, List<String> errors) {
        if (event.data() == null) {
            errors.add("Transaction event must have data payload");
            return;
        }

        JsonNode data = event.data();
        if (!data.has("amount")) {
            errors.add("Transaction event must include amount");
        }
        if (!data.has("currency")) {
            errors.add("Transaction event must include currency");
        }
    }

    /**
     * Validate fraud detection event
     */
    private void validateFraudEvent(Event event, List<String> errors) {
        if (event.data() == null) {
            errors.add("Fraud detection event must have data payload");
            return;
        }

        JsonNode data = event.data();
        if (!data.has("riskScore")) {
            errors.add("Fraud event must include riskScore");
        }
        if (!data.has("reason")) {
            errors.add("Fraud event must include reason");
        }
    }

    /**
     * Validation result
     */
    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }
    }
}
