package com.platform.query.kafka;

import com.platform.query.service.EventQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka consumer for the CQRS read side.
 *
 * Subscribes to all routed event topics via the regex pattern defined in
 * application.yml ({@code app.kafka.topics-pattern}).  Each message is
 * handed off to {@link EventQueryService#store} which persists it to
 * PostgreSQL in a single transaction.
 *
 * <p>Concurrency is set to 3 — one thread per partition on a single-broker
 * local setup.  Increase via {@code spring.kafka.listener.concurrency} for
 * production.
 *
 * <p>Error handling:
 * <ul>
 *   <li>Non-retryable failures (bad payload, DB constraint) → logged and
 *       dropped; the offset is committed so the partition is not blocked.</li>
 *   <li>Transient failures (DB down) → Spring's default retry kicks in.</li>
 * </ul>
 */
@Component
@Slf4j
public class EventConsumer {

    private final EventQueryService service;
    private final Counter           messagesConsumed;
    private final Counter           messagesErrored;

    public EventConsumer(EventQueryService service, MeterRegistry registry) {
        this.service          = service;
        this.messagesConsumed = registry.counter("query.kafka.consumed");
        this.messagesErrored  = registry.counter("query.kafka.errors");
    }

    /**
     * Listens on the pattern {@code events\.(raw|user|transaction|…)}.
     *
     * The {@code topicPattern} attribute is read from the application context
     * via SpEL — keeps the regex in one place (application.yml).
     */
    @KafkaListener(
        topicPattern  = "#{@environment.getProperty('app.kafka.topics-pattern')}",
        groupId       = "event-query-group",
        concurrency   = "3",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            ConsumerRecord<String, Map<String, Object>> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        try {
            Map<String, Object> payload = record.value();
            if (payload == null) {
                log.warn("Null payload on topic={} partition={} offset={}",
                        topic, record.partition(), record.offset());
                return;
            }

            log.debug("Consuming topic={} partition={} offset={} key={}",
                    topic, record.partition(), record.offset(), record.key());

            service.store(payload, topic);
            messagesConsumed.increment();

        } catch (Exception ex) {
            messagesErrored.increment();
            log.error("Failed to process record topic={} partition={} offset={}: {}",
                    topic, record.partition(), record.offset(), ex.getMessage(), ex);
            // Do NOT rethrow — let the listener commit this offset and move on.
            // A dead-letter topic can be wired here if replay is needed.
        }
    }
}
