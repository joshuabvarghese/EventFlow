package com.platform.ingestion.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-registers all Prometheus meters used by the Grafana dashboard.
 *
 * Without explicit registration, counters only appear in /actuator/prometheus
 * after their first increment — causing Grafana panels to show "No data" on
 * first load. Pre-registering with initial value 0 fixes this.
 *
 * Grafana dashboard queries:
 *   sum(rate(eventflow_events_ingested_total[5m]))     → throughput panel
 *   eventflow_events_failed_total                      → failure panel
 *   eventflow_events_duplicates_total                  → dedup panel
 *   histogram_quantile(0.99, eventflow_ingestion_...)  → P99 latency panel
 */
@Configuration
public class MetricsConfig {

    // Shared AtomicLong counters exposed as Gauge metrics so the
    // EventIngestionService can update them without holding a MeterRegistry ref.
    @Bean
    public AtomicLong totalEventsReceived() {
        return new AtomicLong(0);
    }

    @Bean
    public AtomicLong totalEventsProcessed() {
        return new AtomicLong(0);
    }

    @Bean
    public AtomicLong totalEventsFailed() {
        return new AtomicLong(0);
    }

    @Bean
    public AtomicLong totalEventsDuplicated() {
        return new AtomicLong(0);
    }

    @Bean
    public Counter eventsIngestedCounter(MeterRegistry registry) {
        return Counter.builder("eventflow.events.ingested")
                .description("Total number of events successfully ingested")
                .register(registry);
    }

    @Bean
    public Counter eventsFailedCounter(MeterRegistry registry) {
        return Counter.builder("eventflow.events.failed")
                .description("Total number of events that failed ingestion")
                .register(registry);
    }

    @Bean
    public Counter eventsDuplicatesCounter(MeterRegistry registry) {
        return Counter.builder("eventflow.events.duplicates")
                .description("Total number of duplicate events detected and skipped")
                .register(registry);
    }

    @Bean
    public Timer ingestionTimer(MeterRegistry registry) {
        return Timer.builder("eventflow.ingestion.duration")
                .description("Time taken to process and route a single event")
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Gauge that exposes the live success rate — visible in Grafana without PromQL math.
     */
    @Bean
    public Gauge successRateGauge(MeterRegistry registry,
                                   AtomicLong totalEventsReceived,
                                   AtomicLong totalEventsProcessed) {
        return Gauge.builder("eventflow.success.rate", () -> {
            long received = totalEventsReceived.get();
            if (received == 0) return 100.0;
            return (totalEventsProcessed.get() * 100.0) / received;
        })
                .description("Current event processing success rate (%)")
                .baseUnit("percent")
                .register(registry);
    }
}
