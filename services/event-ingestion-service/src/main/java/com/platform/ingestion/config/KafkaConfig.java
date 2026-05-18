package com.platform.ingestion.config;

import com.platform.ingestion.model.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Provides a typed KafkaTemplate bean.
 * All producer tuning (acks, retries, idempotence, compression) lives in
 * application.yml under spring.kafka.producer so it can be overridden per env.
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
