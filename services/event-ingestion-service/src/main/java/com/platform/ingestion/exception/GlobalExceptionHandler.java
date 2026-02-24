package com.platform.ingestion.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised exception handling for all controllers.
 *
 * Ensures every error response follows the same envelope:
 * {
 *   "status":    400,
 *   "error":     "Bad Request",
 *   "message":   "human-readable summary",
 *   "details":   [...],   // optional field-level errors
 *   "timestamp": "..."
 * }
 *
 * This makes it straightforward for the React dashboard to parse errors
 * consistently regardless of which endpoint triggered them.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── Validation errors from @Valid / @RequestBody ─────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        List<String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.toList());

        return error(HttpStatus.BAD_REQUEST, "Request validation failed", fieldErrors);
    }

    // ── Malformed JSON in request body ───────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(
            HttpMessageNotReadableException ex) {

        log.warn("Malformed request body: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Malformed JSON request body", List.of());
    }

    // ── Wrong type for a path/query variable ────────────────────────────────

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        String detail = String.format("Parameter '%s' expects type %s",
                ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        return error(HttpStatus.BAD_REQUEST, detail, List.of());
    }

    // ── EventIngestionService failure ────────────────────────────────────────

    @ExceptionHandler(com.platform.ingestion.service.EventIngestionService.EventIngestionException.class)
    public ResponseEntity<Map<String, Object>> handleIngestionFailure(
            com.platform.ingestion.service.EventIngestionService.EventIngestionException ex) {

        log.error("Event ingestion failure", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "Event ingestion failed — event sent to dead-letter queue", List.of());
    }

    // ── Catch-all ────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", List.of());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message,
                                                       List<String> details) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (!details.isEmpty()) {
            body.put("details", details);
        }
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
