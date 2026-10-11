package com.stockforge.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard event envelope for all asynchronous domain events and commands (spec §18, §20).
 *
 * @param <T> payload type
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

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> create(
            UUID eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            String correlationId,
            String causationId,
            T payload) {
        return new EventEnvelope<>(
                eventId,
                eventType,
                1,
                aggregateType,
                aggregateId,
                occurredAt,
                correlationId,
                causationId != null ? causationId : correlationId,
                payload);
    }
}
