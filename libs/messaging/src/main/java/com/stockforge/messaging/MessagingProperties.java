package com.stockforge.messaging;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration of the messaging infrastructure ({@code stockforge.messaging.*}). Every value has a documented
 * default; a service overrides only what it needs (spec §9 F: no magic numbers).
 *
 * @param outbox transactional outbox relay (spec §20)
 * @param inbox idempotent-consumer inbox (spec §18)
 * @param topics topics this service declares at startup
 * @param consumer retry and dead-letter policy of this service's Kafka listeners (spec §22)
 */
@ConfigurationProperties("stockforge.messaging")
public record MessagingProperties(
        @DefaultValue Outbox outbox,
        @DefaultValue Inbox inbox,
        @DefaultValue Topics topics,
        @DefaultValue Consumer consumer) {

    /**
     * Transactional outbox relay settings.
     *
     * @param relayEnabled whether this instance runs the relay; several instances are safe (one relays at a time)
     * @param pollInterval delay between relay runs; bounds the publish lag when the outbox is idle (NFR-7)
     * @param batchSize maximum events published per run
     * @param sendTimeout how long one batch may wait for broker acknowledgements
     * @param retention how long published rows are kept before the cleanup job deletes them
     * @param cleanupInterval how often the retention job runs
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("200ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("7d") Duration retention,
            @DefaultValue("1h") Duration cleanupInterval) {

        public Outbox {
            requirePositive(pollInterval, "outbox.poll-interval");
            requirePositive(sendTimeout, "outbox.send-timeout");
            requirePositive(retention, "outbox.retention");
            requirePositive(cleanupInterval, "outbox.cleanup-interval");
            if (batchSize < 1 || batchSize > 1_000) {
                throw new IllegalArgumentException("stockforge.messaging.outbox.batch-size must be 1-1000");
            }
        }
    }

    /**
     * Idempotent-consumer inbox settings.
     *
     * @param retention how long processed event ids are remembered. Must exceed the Kafka retention and the longest
     *     replay window, or a replayed message could be applied twice.
     */
    public record Inbox(@DefaultValue("14d") Duration retention) {

        public Inbox {
            requirePositive(retention, "inbox.retention");
        }
    }

    /**
     * Topics created at startup.
     *
     * @param declare topics this service publishes; created at startup if missing
     * @param deadLetters topics this service consumes; their {@code .DLT} topics are created at startup
     * @param partitions partition count of declared topics; caps consumer parallelism
     * @param replicas replication factor (1 on the single local broker)
     */
    public record Topics(
            @DefaultValue List<String> declare,
            @DefaultValue List<String> deadLetters,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {

        public Topics {
            declare = List.copyOf(declare);
            deadLetters = List.copyOf(deadLetters);
            if (partitions < 1) {
                throw new IllegalArgumentException("stockforge.messaging.topics.partitions must be positive");
            }
            if (replicas < 1) {
                throw new IllegalArgumentException("stockforge.messaging.topics.replicas must be positive");
            }
        }
    }

    /**
     * Blocking retries in the listener thread (keeps per-partition order, ADR-011), then the dead-letter topic.
     *
     * @param maxAttempts total deliveries of one message, including the first
     * @param initialBackoff delay before the first retry; doubles up to {@code maxBackoff}, with jitter
     */
    public record Consumer(
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("5s") Duration maxBackoff) {

        public Consumer {
            requirePositive(initialBackoff, "consumer.initial-backoff");
            requirePositive(maxBackoff, "consumer.max-backoff");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("stockforge.messaging.consumer.max-attempts must be positive");
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("stockforge.messaging." + name + " must be a positive duration");
        }
    }
}
