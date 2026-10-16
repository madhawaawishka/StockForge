package com.stockforge.payment.domain.port;

import java.util.Objects;

/**
 * Records a message to publish, in the current transaction (transactional outbox, spec §20). The message is sent to
 * Kafka only if the transaction commits.
 */
public interface OutboxPublisher {

    void publish(OutboundMessage message);

    /**
     * A message waiting in the outbox.
     *
     * @param topic destination topic
     * @param aggregateType aggregate the message is about
     * @param aggregateId aggregate id; also the partition key
     * @param eventType envelope event type
     * @param payload payload record
     * @param correlationId business flow the message belongs to
     * @param causationId event id of the message that caused this one, or the correlation id for user requests
     */
    record OutboundMessage(
            String topic,
            String aggregateType,
            String aggregateId,
            String eventType,
            Object payload,
            String correlationId,
            String causationId) {

        public OutboundMessage {
            Objects.requireNonNull(topic, "topic must not be null");
            Objects.requireNonNull(aggregateType, "aggregateType must not be null");
            Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
            Objects.requireNonNull(causationId, "causationId must not be null");
        }
    }
}
