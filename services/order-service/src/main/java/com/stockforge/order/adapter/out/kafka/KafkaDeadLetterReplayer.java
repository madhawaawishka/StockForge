package com.stockforge.order.adapter.out.kafka;

import com.stockforge.events.Topics;
import com.stockforge.order.domain.port.DeadLetterReplayer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Replays dead-lettered messages (spec §22). Reads {@code <topic>.DLT} with its own consumer group, so successive
 * replays continue where the last one stopped, and republishes each record to the topic it originally failed on.
 *
 * <p>The record keeps its key (so it lands on the same partition as the rest of its aggregate) and its envelope and
 * event-id header (so the consumer's inbox still deduplicates it). Spring's {@code kafka_dlt-*} diagnostic headers are
 * dropped. The DLT offset is committed only after the republished record was acknowledged, so a crash replays a record
 * at most twice, which the idempotent consumers absorb.
 */
@Component
class KafkaDeadLetterReplayer implements DeadLetterReplayer {

    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterReplayer.class);
    private static final String REPLAY_GROUP = "stockforge-dead-letter-replay";
    private static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;

    KafkaDeadLetterReplayer(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        this.consumers = consumers;
        this.kafka = kafka;
    }

    /** Synchronized: replays of this instance never interleave. */
    @Override
    public synchronized int replay(String topic, int maxMessages) {
        String deadLetterTopic = Topics.deadLetterTopicOf(topic);
        int replayed = 0;
        try (Consumer<String, String> consumer = consumers.createConsumer(REPLAY_GROUP, "replay")) {
            List<TopicPartition> partitions = consumer.partitionsFor(deadLetterTopic).stream()
                    .map(info -> new TopicPartition(deadLetterTopic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            while (replayed < maxMessages) {
                ConsumerRecords<String, String> batch = consumer.poll(POLL_TIMEOUT);
                if (batch.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> deadLetter : batch) {
                    if (replayed >= maxMessages) {
                        break;
                    }
                    republish(deadLetter, topic);
                    consumer.commitSync(Map.of(
                            new TopicPartition(deadLetter.topic(), deadLetter.partition()),
                            new OffsetAndMetadata(deadLetter.offset() + 1)));
                    replayed++;
                }
            }
        }
        log.info("Replayed {} message(s) from {}", replayed, deadLetterTopic);
        return replayed;
    }

    private void republish(ConsumerRecord<String, String> deadLetter, String fallbackTopic) {
        Header original = deadLetter.headers().lastHeader(ORIGINAL_TOPIC_HEADER);
        String target = original == null ? fallbackTopic : new String(original.value(), StandardCharsets.UTF_8);
        ProducerRecord<String, String> record = new ProducerRecord<>(target, deadLetter.key(), deadLetter.value());
        Map<String, Header> kept = new HashMap<>();
        for (Header header : deadLetter.headers()) {
            if (!header.key().startsWith("kafka_dlt-")) {
                kept.put(header.key(), header);
            }
        }
        kept.values().forEach(header -> record.headers().add(header));
        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying a dead letter", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("Could not republish dead letter to " + target, failed);
        }
    }
}
