package com.stockforge.messaging;

import java.util.Objects;

/**
 * A message to append to the outbox.
 *
 * @param topic destination topic, one of {@link com.stockforge.events.Topics}
 * @param aggregateType aggregate the message is about, for example {@code "Order"}
 * @param aggregateId aggregate id; also the Kafka key, so messages of one aggregate stay in order
 * @param eventType envelope type, the {@code TYPE} constant of the payload record
 * @param payload payload record from {@code event-schemas}
 * @param correlationId id shared by every message of one business flow (usually the order or reservation id)
 * @param causationId event id of the message that caused this one, or the correlation id for user requests
 */
public record OutgoingMessage(
        String topic,
        String aggregateType,
        String aggregateId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId) {

    public OutgoingMessage {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
    }
}
