package com.platform.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Event Ingestion Service
 * 
 * High-throughput event ingestion service that accepts events via REST API,
 * validates them, deduplicates using Redis, and publishes to Kafka topics.
 * 
 * Features:
 * - Multi-protocol ingestion (REST, WebSocket, gRPC)
 * - Schema validation
 * - Deduplication
 * - Dead letter queue for failed events
 * - Prometheus metrics
 * - Distributed tracing
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
public class EventIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventIngestionApplication.class, args);
    }
}
