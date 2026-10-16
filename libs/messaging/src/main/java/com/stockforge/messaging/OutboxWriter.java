package com.stockforge.messaging;

import com.stockforge.events.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends messages to the {@code outbox_events} table of the calling service (spec §20).
 *
 * <p>The row is written in the caller's database transaction, so the business change and its event commit or roll back
 * together; {@link OutboxRelay} publishes the row later. Calling this without a transaction is a bug, because the event
 * could then outlive a rolled-back change, so it fails fast.
 */
public final class OutboxWriter {

    private final JdbcClient jdbc;
    private final EnvelopeCodec codec;
    private final TraceContextSupport tracing;
    private final Clock clock;

    OutboxWriter(JdbcClient jdbc, EnvelopeCodec codec, TraceContextSupport tracing, Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.tracing = tracing;
        this.clock = clock;
    }

    /** Returns the event id, which consumers use to deduplicate redeliveries. */
    public UUID append(OutgoingMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox writes must join the business transaction; no transaction is active for "
                            + message.eventType());
        }
        UUID eventId = TimeOrderedIds.next(clock);
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        EventEnvelope<Object> envelope = new EventEnvelope<>(
                eventId,
                message.eventType(),
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                message.aggregateType(),
                message.aggregateId(),
                occurredAt,
                message.correlationId(),
                message.causationId(),
                message.payload());

        jdbc.sql("""
                        INSERT INTO outbox_events (id, topic, aggregate_type, aggregate_id, event_type, payload, headers,
                                                   status, created_at)
                        VALUES (:id, :topic, :aggregateType, :aggregateId, :eventType,
                                CAST(:payload AS JSONB), CAST(:headers AS JSONB), 'PENDING', :createdAt)
                        """)
                .param("id", eventId)
                .param("topic", message.topic())
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("eventType", message.eventType())
                .param("payload", codec.encode(envelope))
                .param("headers", codec.encodeHeaders(tracing.captureCurrent()))
                .param("createdAt", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
        return eventId;
    }
}
