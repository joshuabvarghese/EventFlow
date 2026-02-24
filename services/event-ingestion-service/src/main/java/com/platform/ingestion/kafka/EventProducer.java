package com.platform.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.model.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer for sending events to topics.
 * Implements best practices for reliability and observability.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventProducer {

    private final KafkaTemplate<String, Event> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Send event to Kafka topic asynchronously
     */
    public CompletableFuture<SendResult<String, Event>> send(String topic, String key, Event event) {
        try {
            // Create producer record with headers
            ProducerRecord<String, Event> record = new ProducerRecord<>(
                    topic,
                    null, // partition - let Kafka decide based on key
                    key,
                    event,
                    createHeaders(event)
            );

            // Send asynchronously
            return kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send event to Kafka: topic={}, key={}, eventId={}", 
                                     topic, key, event.eventId(), ex);
                        } else {
                            log.debug("Event sent successfully: topic={}, partition={}, offset={}, eventId={}", 
                                     topic, 
                                     result.getRecordMetadata().partition(),
                                     result.getRecordMetadata().offset(),
                                     event.eventId());
                        }
                    });

        } catch (Exception e) {
            log.error("Error creating Kafka producer record: eventId={}", event.eventId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Send event synchronously (blocks until acknowledgment)
     */
    public SendResult<String, Event> sendSync(String topic, String key, Event event) throws Exception {
        return send(topic, key, event).get();
    }

    /**
     * Create Kafka headers for tracing and metadata
     */
    private List<Header> createHeaders(Event event) {
        List<Header> headers = new ArrayList<>();
        
        // Add correlation ID for distributed tracing
        if (event.correlationId() != null) {
            headers.add(new RecordHeader("correlationId", 
                    event.correlationId().getBytes(StandardCharsets.UTF_8)));
        }

        // Add event type for filtering
        headers.add(new RecordHeader("eventType", 
                event.eventType().getBytes(StandardCharsets.UTF_8)));

        // Add source system
        if (event.source() != null) {
            headers.add(new RecordHeader("source", 
                    event.source().getBytes(StandardCharsets.UTF_8)));
        }

        // Add schema version
        if (event.version() != null) {
            headers.add(new RecordHeader("schemaVersion", 
                    event.version().getBytes(StandardCharsets.UTF_8)));
        }

        return headers;
    }

    /**
     * Check if Kafka is healthy
     */
    public boolean isHealthy() {
        try {
            // Try to get metadata - this will fail if Kafka is unreachable
            kafkaTemplate.execute((producerCallback) -> {
                producerCallback.partitionsFor("events.raw");
                return true;
            });
            return true;
        } catch (Exception e) {
            log.error("Kafka health check failed", e);
            return false;
        }
    }

    /**
     * Flush any pending sends (useful for graceful shutdown)
     */
    public void flush() {
        kafkaTemplate.flush();
        log.info("Kafka producer flushed");
    }
}
