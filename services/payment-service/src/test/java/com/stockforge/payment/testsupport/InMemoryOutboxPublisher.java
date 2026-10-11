package com.stockforge.payment.testsupport;

import com.stockforge.payment.domain.port.OutboxPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InMemoryOutboxPublisher implements OutboxPublisher {

    public record PublishedEvent(
            String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {}

    private final List<PublishedEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(
            String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {
        events.add(new PublishedEvent(aggregateType, aggregateId, eventType, payload, correlationId));
    }

    public List<PublishedEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
