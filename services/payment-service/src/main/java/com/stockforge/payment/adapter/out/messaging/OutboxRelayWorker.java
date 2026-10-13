package com.stockforge.payment.adapter.out.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.events.DomainEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Transactional Outbox Relay worker polling pending events with FOR UPDATE SKIP LOCKED (spec §20). */
@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);

    public OutboxRelayWorker(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (meterRegistry != null) {
            meterRegistry.gauge("stockforge.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds);
        }
    }

    public record OutboxRecord(
            UUID id, String aggregateType, String aggregateId, String eventType, String payload, String headers) {}

    @Scheduled(fixedDelayString = "${stockforge.outbox.poll-interval-ms:500}")
    @Transactional
    public void relayPendingEvents() {
        Double oldestAge = jdbc.sql("""
                SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at)))
                FROM outbox_events
                WHERE status = 'PENDING'
                """).query(Double.class).optional().orElse(0.0);
        oldestPendingAgeSeconds.set(oldestAge != null ? oldestAge.longValue() : 0);

        List<OutboxRecord> pending =
                jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload, headers
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        ORDER BY created_at
                        LIMIT 100
                        FOR UPDATE SKIP LOCKED
                        """).query(OutboxRelayWorker::mapRow).list();

        if (pending.isEmpty()) {
            return;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        for (OutboxRecord record : pending) {
            String topic = DomainEvents.TOPIC_PAYMENT_EVENTS;
            try {
                if (kafkaTemplate != null) {
                    ProducerRecord<String, String> producerRecord =
                            new ProducerRecord<>(topic, null, record.aggregateId(), record.payload());

                    extractAndAttachHeaders(record.headers(), producerRecord);

                    kafkaTemplate.send(producerRecord).get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
                jdbc.sql("""
                                UPDATE outbox_events
                                SET status = 'PUBLISHED', published_at = :now, updated_at = :now
                                WHERE id = :id
                                """)
                        .param("id", record.id())
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update();
                log.debug("Published payment outbox event {} of type {} to {}", record.id(), record.eventType(), topic);
            } catch (Exception e) {
                log.error("Failed to relay outbox event {}: {}", record.id(), e.getMessage());
                jdbc.sql("""
                                UPDATE outbox_events
                                SET attempts = attempts + 1, last_error = :err, updated_at = :now
                                WHERE id = :id
                                """)
                        .param("id", record.id())
                        .param("err", e.getMessage() == null ? "Unknown error" : e.getMessage())
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update();
            }
        }
    }

    private void extractAndAttachHeaders(String headersJson, ProducerRecord<String, String> producerRecord) {
        if (headersJson == null || headersJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(headersJson);
            if (node.hasNonNull("traceparent")) {
                producerRecord
                        .headers()
                        .add("traceparent", node.get("traceparent").asText().getBytes(StandardCharsets.UTF_8));
            }
            if (node.hasNonNull("correlationId")) {
                producerRecord
                        .headers()
                        .add("correlationId", node.get("correlationId").asText().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("Failed to parse headers JSON for outbox event: {}", e.getMessage());
        }
    }

    private static OutboxRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRecord(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("headers"));
    }
}
