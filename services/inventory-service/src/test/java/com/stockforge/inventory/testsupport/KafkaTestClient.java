package com.stockforge.inventory.testsupport;

import com.stockforge.events.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plays the other services in integration tests: sends commands and reads what this service published. */
public final class KafkaTestClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String bootstrapServers;

    public KafkaTestClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Sends an envelope exactly as another service's outbox relay would. */
    public void send(String topic, String key, EventEnvelope<?> envelope) {
        sendRaw(topic, key, JSON.writeValueAsString(envelope));
    }

    public void sendRaw(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test message to " + topic, e);
        }
    }

    public static <T> EventEnvelope<T> envelope(String eventType, String aggregateType, String aggregateId, T payload) {
        UUID eventId = UUID.randomUUID();
        return new EventEnvelope<>(
                eventId,
                eventType,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                aggregateType,
                aggregateId,
                Instant.now(),
                aggregateId,
                aggregateId,
                payload);
    }

    /** Reads the topic from the beginning until a record matches, or fails after the timeout. */
    public ConsumerRecord<String, String> awaitRecord(
            String topic, Predicate<ConsumerRecord<String, String>> matching, Duration timeout) {
        return awaitRecords(topic, matching, 1, timeout).getFirst();
    }

    public List<ConsumerRecord<String, String>> awaitRecords(
            String topic, Predicate<ConsumerRecord<String, String>> matching, int count, Duration timeout) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            Instant deadline = Instant.now().plus(timeout);
            while (found.size() < count && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (matching.test(record)) {
                        found.add(record);
                    }
                });
            }
            if (found.size() < count) {
                throw new AssertionError("Expected " + count + " matching record(s) on " + topic + " within " + timeout
                        + ", found " + found.size());
            }
            return found;
        }
    }

    /** Counts matching records currently on the topic (reads for a short, fixed window). */
    public long count(String topic, Predicate<ConsumerRecord<String, String>> matching, Duration window) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long matches = 0;
            Instant end = Instant.now().plus(window);
            while (Instant.now().isBefore(end)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (matching.test(record)) {
                        matches++;
                    }
                }
            }
            return matches;
        }
    }

    public static JsonNode json(ConsumerRecord<String, String> record) {
        return JSON.readTree(record.value());
    }

    public static Predicate<ConsumerRecord<String, String>> ofType(String eventType, String aggregateId) {
        return record -> {
            JsonNode envelope = json(record);
            return eventType.equals(envelope.path("eventType").asString())
                    && aggregateId.equals(envelope.path("aggregateId").asString());
        };
    }

    public static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
