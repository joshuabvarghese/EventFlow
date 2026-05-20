package com.platform.query.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer infrastructure.
 *
 * Key decisions:
 * <ul>
 *   <li>Uses {@link ErrorHandlingDeserializer} wrapping {@link JsonDeserializer}
 *       so a single malformed message does not poison the consumer thread.</li>
 *   <li>{@code AckMode.BATCH} — offsets committed after each poll batch,
 *       balancing throughput vs. duplicate-on-restart risk.</li>
 *   <li>Trusted packages = "*" because the ingestion service strips type
 *       headers; we deserialise everything as {@code Map<String, Object>}.</li>
 * </ul>
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Map<String, Object>> consumerFactory(ObjectMapper objectMapper) {
        // Wire our shared ObjectMapper into the deserialiser so date handling
        // (JavaTimeModule) is consistent across the whole application.
        JsonDeserializer<Map<String, Object>> jsonDeser = new JsonDeserializer<>(objectMapper);
        jsonDeser.addTrustedPackages("*");
        jsonDeser.setUseTypeHeaders(false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,           "event-query-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,   500);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(jsonDeser)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>
    kafkaListenerContainerFactory(ConsumerFactory<String, Map<String, Object>> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        return factory;
    }
}
