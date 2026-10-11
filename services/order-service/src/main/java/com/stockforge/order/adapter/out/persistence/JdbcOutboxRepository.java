package com.stockforge.order.adapter.out.persistence;

import static com.stockforge.order.adapter.out.persistence.SqlTypes.toTimestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends outbox events to the PostgreSQL outbox_events table in the current business transaction (spec §20). */
@Repository
class JdbcOutboxRepository implements OutboxPublisher {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JdbcOutboxRepository(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void publish(
            String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox event payload", e);
        }

        String headersJson;
        try {
            headersJson = objectMapper.writeValueAsString(Map.of(
                    "correlationId", correlationId,
                    "eventType", eventType,
                    "aggregateType", aggregateType,
                    "aggregateId", aggregateId));
        } catch (JsonProcessingException e) {
            headersJson = "{}";
        }

        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, headers, status, created_at)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, :payload, :headers, 'PENDING', :createdAt)
                        """)
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("headers", headersJson)
                .param("createdAt", toTimestamp(now))
                .update();
    }
}
