package com.stockforge.order.domain.port;

/** Secondary port for appending outbox events in the same database transaction as business state (spec §20). */
public interface OutboxPublisher {

    void publish(String aggregateType, String aggregateId, String eventType, Object payload, String correlationId);

    OutboxPublisher NO_OP = (aggregateType, aggregateId, eventType, payload, correlationId) -> {};
}
