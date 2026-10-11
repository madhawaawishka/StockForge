package com.stockforge.inventory.domain.port;

/** Appends outbox events in the current transactional boundary (spec §20). */
public interface OutboxPublisher {

    void publish(String aggregateType, String aggregateId, String eventType, Object payload, String correlationId);
}
