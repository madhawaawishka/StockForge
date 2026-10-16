package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.port.OutboxPublisher;
import java.util.ArrayList;
import java.util.List;

/** Records outbox messages so tests can assert which events a use case emitted. */
public class InMemoryOutbox implements OutboxPublisher {

    private final List<OutboundMessage> messages = new ArrayList<>();

    @Override
    public synchronized void publish(OutboundMessage message) {
        messages.add(message);
    }

    public synchronized List<OutboundMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized List<String> eventTypes() {
        return messages.stream().map(OutboundMessage::eventType).toList();
    }

    public synchronized <T> List<T> payloads(Class<T> type) {
        return messages.stream()
                .map(OutboundMessage::payload)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
