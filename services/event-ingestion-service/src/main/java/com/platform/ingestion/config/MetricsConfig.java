package com.platform.ingestion.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-registers all Prometheus meters used by the service.
 *
 * Shared AtomicLong beans are the single source of truth for counters — both
 * the in-memory stats API and the Prometheus Gauges read from them, so the
 * numbers are always consistent.
 *
 * Pre-registration ensures meters appear in /actuator/prometheus with value 0
 * before the first event arrives, preventing "No data" in Grafana on cold start.
 */
@Configuration
public class MetricsConfig {

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
                .description("Total events successfully ingested")
                .register(registry);
    }

    @Bean
    public Counter eventsFailedCounter(MeterRegistry registry) {
        return Counter.builder("eventflow.events.failed")
                .description("Total events that failed ingestion")
                .register(registry);
    }

    @Bean
    public Counter eventsDuplicatesCounter(MeterRegistry registry) {
        return Counter.builder("eventflow.events.duplicates")
                .description("Total duplicate events skipped")
                .register(registry);
    }

    @Bean
    public Timer ingestionTimer(MeterRegistry registry) {
        return Timer.builder("eventflow.ingestion.duration")
                .description("Time to process and route a single event")
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Gauge for live success rate.
     *
     * Note: Gauge.builder().register() returns a Gauge — we declare the bean
     * return type as Gauge (not void) so Spring stores it and keeps a strong
     * reference. Micrometer Gauges are weakly referenced internally; if nothing
     * holds a strong reference the gauge is GC'd and disappears from metrics.
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
