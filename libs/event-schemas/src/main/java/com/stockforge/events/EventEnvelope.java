package com.stockforge.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The envelope around every Kafka message (spec §18). It is the JSON value of the record.
 *
 * <p>{@code eventId} is the deduplication key of the idempotent consumers (inbox pattern), so it is unique per message
 * and stable across redeliveries. {@code correlationId} ties every message of one checkout together;
 * {@code causationId} is the id of the message that caused this one.
 *
 * <p>Schema evolution: within one {@code schemaVersion} payloads only gain optional fields; a breaking change bumps the
 * version and consumers accept both versions during the migration (ADR-012).
 *
 * @param <T> payload type; consumers first read the envelope with an untyped payload, then convert the payload by
 *     {@code eventType}
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }
}
