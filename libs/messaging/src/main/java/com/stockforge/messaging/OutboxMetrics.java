package com.stockforge.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Outbox health metrics (spec §25). The age of the oldest pending event is the key signal: it grows when Kafka is
 * unreachable or the relay is stuck, and alerts fire on it (NFR-7, RUNBOOK-003).
 *
 * <ul>
 *   <li>{@code stockforge_outbox_pending_events} — rows waiting to be published
 *   <li>{@code stockforge_outbox_oldest_pending_age_seconds} — age of the oldest of them, measured with database time
 *   <li>{@code stockforge_outbox_published_total}, {@code stockforge_outbox_publish_failures_total}
 *   <li>{@code stockforge_outbox_publish_lag_seconds} — commit-to-acknowledgement time per event, the NFR-7 SLI (P95
 *       &lt; 1 s); the 1 s bucket makes "share published within a second" a plain ratio of two counters
 * </ul>
 */
final class OutboxMetrics {

    private final AtomicLong pending = new AtomicLong();
    // Double stored as raw bits so that a scrape never observes a half-updated value.
    private final AtomicLong oldestAgeSecondsBits = new AtomicLong(Double.doubleToLongBits(0));
    private final Counter published;
    private final Counter failures;
    private final Timer publishLag;

    OutboxMetrics(MeterRegistry registry) {
        Gauge.builder("stockforge.outbox.pending.events", pending, AtomicLong::get)
                .description("Outbox events not yet published")
                .register(registry);
        Gauge.builder(
                        "stockforge.outbox.oldest.pending.age",
                        oldestAgeSecondsBits,
                        bits -> Double.longBitsToDouble(bits.get()))
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox event")
                .register(registry);
        published = Counter.builder("stockforge.outbox.published")
                .description("Outbox events published to Kafka")
                .register(registry);
        failures = Counter.builder("stockforge.outbox.publish.failures")
                .description("Failed attempts to publish an outbox event")
                .register(registry);
        publishLag = Timer.builder("stockforge.outbox.publish.lag")
                .description("Time from writing an outbox event to the broker acknowledging it")
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30))
                .register(registry);
    }

    void recordBacklog(JdbcClient jdbc) {
        jdbc.sql("""
                        SELECT (SELECT count(*) FROM outbox_events WHERE status = 'PENDING') AS pending,
                               COALESCE((SELECT EXTRACT(EPOCH FROM now() - created_at)
                                         FROM outbox_events WHERE status = 'PENDING'
                                         ORDER BY seq LIMIT 1), 0) AS oldest_age
                        """).query(rs -> {
            pending.set(rs.getLong("pending"));
            oldestAgeSecondsBits.set(Double.doubleToLongBits(Math.max(0, rs.getDouble("oldest_age"))));
        });
    }

    void recordPublished(int count) {
        published.increment(count);
    }

    void recordPublishLag(double seconds) {
        publishLag.record(Duration.ofNanos((long) (Math.max(0, seconds) * 1_000_000_000L)));
    }

    void recordFailure() {
        failures.increment();
    }
}
