package com.platform.ingestion.kafka;

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
 * Kafka producer wrapping KafkaTemplate with header enrichment and health-check support.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventProducer {

    private final KafkaTemplate<String, Event> kafkaTemplate;

    /**
     * Sends an event to the given topic asynchronously.
     * The returned future completes when the broker acknowledges the write.
     */
    public CompletableFuture<SendResult<String, Event>> send(String topic, String key, Event event) {
        try {
            ProducerRecord<String, Event> record = new ProducerRecord<>(
                    topic,
                    null,   // partition — let Kafka decide based on key hash
                    key,
                    event,
                    buildHeaders(event)
            );

            return kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Kafka send failed: topic={}, eventId={}", topic, event.eventId(), ex);
                        } else {
                            log.debug("Kafka send OK: topic={}, partition={}, offset={}, eventId={}",
                                    topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    event.eventId());
                        }
                    });

        } catch (Exception e) {
            log.error("Failed to build producer record: eventId={}", event.eventId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Blocks until the broker acknowledges — used for health-check only. */
    public boolean isHealthy() {
        try {
            kafkaTemplate.execute(producer -> {
                producer.partitionsFor("events.raw");
                return null;
            });
            return true;
        } catch (Exception e) {
            log.warn("Kafka health check failed: {}", e.getMessage());
            return false;
        }
    }

    public void flush() {
        kafkaTemplate.flush();
    }

    private List<Header> buildHeaders(Event event) {
        List<Header> headers = new ArrayList<>();
        headers.add(new RecordHeader("eventType",
                event.eventType().getBytes(StandardCharsets.UTF_8)));
        if (event.correlationId() != null) {
            headers.add(new RecordHeader("correlationId",
                    event.correlationId().getBytes(StandardCharsets.UTF_8)));
        }
        if (event.source() != null) {
            headers.add(new RecordHeader("source",
                    event.source().getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }
}
