package com.stockforge.payment.domain.port;

public interface OutboxPublisher {

    void publish(String aggregateType, String aggregateId, String eventType, Object payload, String correlationId);
}
