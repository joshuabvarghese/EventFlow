package com.platform.ingestion.config;

import com.platform.ingestion.model.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka configuration.
 *
 * Producer settings (acks, retries, idempotence, compression, etc.) are declared
 * in application.yml under spring.kafka.producer so they can be overridden per
 * environment without code changes. This class only provides the typed KafkaTemplate
 * bean — eliminating the duplication that existed between ProducerConfig constants
 * and the YAML properties.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public KafkaTemplate<String, Event> kafkaTemplate(ProducerFactory<String, Event> producerFactory) {
        KafkaTemplate<String, Event> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        return template;
    }
}
