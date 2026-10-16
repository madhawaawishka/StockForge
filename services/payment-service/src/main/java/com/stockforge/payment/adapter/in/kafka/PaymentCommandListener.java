package com.stockforge.payment.adapter.in.kafka;

import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.messaging.EnvelopeCodec;
import com.stockforge.messaging.IncomingMessage;
import com.stockforge.messaging.PoisonMessageException;
import com.stockforge.payment.application.ProcessPaymentUseCase;
import com.stockforge.payment.application.RefundPaymentUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the saga's payment commands (spec §16 B). Both are keyed by order id, so the charge and a later refund of
 * the same order are processed in order. Failures are retried and then dead-lettered by the shared error handler.
 */
@Component
class PaymentCommandListener {

    private final ProcessPaymentUseCase processPayment;
    private final RefundPaymentUseCase refundPayment;
    private final EnvelopeCodec codec;

    PaymentCommandListener(
            ProcessPaymentUseCase processPayment, RefundPaymentUseCase refundPayment, EnvelopeCodec codec) {
        this.processPayment = processPayment;
        this.refundPayment = refundPayment;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "payment-service")
    void onCommand(ConsumerRecord<String, String> record) {
        IncomingMessage message = codec.decode(record.topic(), record.value());
        switch (message.eventType()) {
            case PaymentMessages.RequestPayment.TYPE ->
                processPayment.execute(message.eventId(), message.payloadAs(PaymentMessages.RequestPayment.class));
            case PaymentMessages.RefundPayment.TYPE ->
                refundPayment.execute(message.eventId(), message.payloadAs(PaymentMessages.RefundPayment.class));
            default -> throw new PoisonMessageException("Unknown payment command " + message);
        }
    }
}
