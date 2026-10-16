package com.stockforge.messaging;

import com.stockforge.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer failure policy (spec §22, ADR-014).
 *
 * <ul>
 *   <li>Transient failures (database timeout, deadlock, broker hiccup) are retried in place with exponential backoff
 *       and jitter. Retrying in the listener thread blocks the partition but keeps per-aggregate order, which the saga
 *       relies on.
 *   <li>Poison messages ({@link PoisonMessageException}) are not retried at all.
 *   <li>After the last attempt the message goes to {@code <topic>.DLT} on the same partition. Spring Kafka adds the
 *       original topic, partition, offset, exception class and message as headers, so it can be diagnosed and replayed.
 *       {@code stockforge_kafka_dead_lettered_total} counts them, and an alert fires on any increase.
 *   <li>The counter of every consumed topic exists from start-up, at zero: Prometheus' {@code increase()} cannot see
 *       the first sample of a new series, so a counter created by the first dead letter would never fire the alert for
 *       it.
 * </ul>
 */
final class DeadLetterErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterErrorHandling.class);

    private DeadLetterErrorHandling() {}

    /** @param consumedTopics the topics whose dead letters are counted from start-up */
    static DefaultErrorHandler errorHandler(
            KafkaOperations<?, ?> kafka,
            MessagingProperties.Consumer config,
            List<String> consumedTopics,
            MeterRegistry registry) {
        consumedTopics.forEach(topic -> deadLettered(registry, topic));
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafka,
                (record, exception) ->
                        new TopicPartition(Topics.deadLetterTopicOf(record.topic()), record.partition()));

        ExponentialBackOff backOff =
                new ExponentialBackOff(config.initialBackoff().toMillis(), 2.0);
        backOff.setMaxInterval(config.maxBackoff().toMillis());
        // Jitter spreads retries of many consumers so they do not hammer a recovering dependency in lockstep.
        backOff.setJitter(config.initialBackoff().toMillis() / 2);
        backOff.setMaxAttempts(config.maxAttempts() - 1);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error(
                            "Dead-lettering {}-{}@{}: {}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            exception.getMessage());
                    deadLettered(registry, record.topic()).increment();
                    deadLetters.accept(record, exception);
                },
                backOff);
        handler.addNotRetryableExceptions(PoisonMessageException.class);
        return handler;
    }

    private static Counter deadLettered(MeterRegistry registry, String topic) {
        return Counter.builder("stockforge.kafka.dead.lettered")
                .description("Messages moved to a dead-letter topic")
                .tag("topic", topic)
                .register(registry);
    }
}
