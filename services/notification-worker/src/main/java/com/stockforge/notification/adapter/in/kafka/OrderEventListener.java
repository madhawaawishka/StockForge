package com.stockforge.notification.adapter.in.kafka;

import com.stockforge.events.OrderMessages;
import com.stockforge.events.Topics;
import com.stockforge.messaging.EnvelopeCodec;
import com.stockforge.messaging.IncomingMessage;
import com.stockforge.messaging.PoisonMessageException;
import com.stockforge.notification.application.NotifyCustomerUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns order events into customer notifications (spec §10). Retries and dead-lettering come from the shared handler.
 */
@Component
class OrderEventListener {

    private final NotifyCustomerUseCase notify;
    private final EnvelopeCodec codec;

    OrderEventListener(NotifyCustomerUseCase notify, EnvelopeCodec codec) {
        this.notify = notify;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "notification-worker")
    void onOrderEvent(ConsumerRecord<String, String> record) {
        IncomingMessage message = codec.decode(record.topic(), record.value());
        switch (message.eventType()) {
            case OrderMessages.OrderConfirmed.TYPE ->
                notify.orderConfirmed(message.eventId(), message.payloadAs(OrderMessages.OrderConfirmed.class));
            case OrderMessages.OrderCancelled.TYPE ->
                notify.orderCancelled(message.eventId(), message.payloadAs(OrderMessages.OrderCancelled.class));
            case OrderMessages.OrderRefundPending.TYPE ->
                notify.refundPending(message.eventId(), message.payloadAs(OrderMessages.OrderRefundPending.class));
            case OrderMessages.OrderRefunded.TYPE ->
                notify.refunded(message.eventId(), message.payloadAs(OrderMessages.OrderRefunded.class));
            case OrderMessages.OrderCreated.TYPE -> {
                // No notification for a placed order: the customer hears about its outcome.
            }
            default -> throw new PoisonMessageException("Unknown order event " + message);
        }
    }
}
