package com.stockforge.order.adapter.in.kafka;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.messaging.EnvelopeCodec;
import com.stockforge.messaging.IncomingMessage;
import com.stockforge.messaging.PoisonMessageException;
import com.stockforge.order.application.CheckoutSaga;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds payment and inventory events into the checkout saga (spec §16 B). Offsets are committed only after the saga's
 * transaction committed (ack mode RECORD); failures are retried in place and then dead-lettered by the shared error
 * handler, so the partition keeps flowing and per-order ordering is preserved (ADR-011).
 */
@Component
class SagaEventListeners {

    private final CheckoutSaga saga;
    private final EnvelopeCodec codec;

    SagaEventListeners(CheckoutSaga saga, EnvelopeCodec codec) {
        this.saga = saga;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "order-service-payment-events")
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        IncomingMessage message = codec.decode(record.topic(), record.value());
        switch (message.eventType()) {
            case PaymentMessages.PaymentSucceeded.TYPE ->
                saga.onPaymentSucceeded(message.eventId(), message.payloadAs(PaymentMessages.PaymentSucceeded.class));
            case PaymentMessages.PaymentFailed.TYPE -> {
                var failed = message.payloadAs(PaymentMessages.PaymentFailed.class);
                saga.onPaymentFailed(message.eventId(), failed.orderId(), "Payment failed: " + failed.reason());
            }
            case PaymentMessages.PaymentTimedOut.TYPE -> {
                var timedOut = message.payloadAs(PaymentMessages.PaymentTimedOut.class);
                saga.onPaymentFailed(message.eventId(), timedOut.orderId(), "Payment timed out");
            }
            case PaymentMessages.PaymentRefunded.TYPE ->
                saga.onPaymentRefunded(message.eventId(), message.payloadAs(PaymentMessages.PaymentRefunded.class));
            default -> throw new PoisonMessageException("Unknown payment event " + message);
        }
    }

    @KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "order-service-inventory-events")
    void onInventoryEvent(ConsumerRecord<String, String> record) {
        IncomingMessage message = codec.decode(record.topic(), record.value());
        switch (message.eventType()) {
            case InventoryMessages.ReservationConfirmed.TYPE ->
                saga.onReservationConfirmed(
                        message.eventId(), message.payloadAs(InventoryMessages.ReservationConfirmed.class));
            case InventoryMessages.ReservationConfirmationRejected.TYPE ->
                saga.onReservationConfirmationRejected(
                        message.eventId(), message.payloadAs(InventoryMessages.ReservationConfirmationRejected.class));
            case InventoryMessages.ReservationCreated.TYPE,
                    InventoryMessages.ReservationReleased.TYPE,
                    InventoryMessages.ReservationExpired.TYPE -> {
                // Facts the saga does not act on: an order learns about a lost reservation through the
                // confirmation reply, which is what decides between confirming and refunding.
            }
            default -> throw new PoisonMessageException("Unknown inventory event " + message);
        }
    }
}
