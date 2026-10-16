package com.stockforge.payment.adapter.out.messaging;

import com.stockforge.messaging.OutboxWriter;
import com.stockforge.messaging.OutgoingMessage;
import com.stockforge.messaging.ProcessedMessageStore;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.ProcessedMessages;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Connects the domain's outbox and inbox ports to the shared messaging infrastructure (spec §18, §20). */
final class MessagingAdapters {

    private MessagingAdapters() {}

    @Component
    static class TransactionalOutbox implements OutboxPublisher {

        private final OutboxWriter writer;

        TransactionalOutbox(OutboxWriter writer) {
            this.writer = writer;
        }

        @Override
        public void publish(OutboundMessage message) {
            writer.append(new OutgoingMessage(
                    message.topic(),
                    message.aggregateType(),
                    message.aggregateId(),
                    message.eventType(),
                    message.payload(),
                    message.correlationId(),
                    message.causationId()));
        }
    }

    @Component
    static class Inbox implements ProcessedMessages {

        private final ProcessedMessageStore store;

        Inbox(ProcessedMessageStore store) {
            this.store = store;
        }

        @Override
        public boolean markProcessed(String consumer, UUID eventId) {
            return store.markProcessed(consumer, eventId);
        }
    }
}
