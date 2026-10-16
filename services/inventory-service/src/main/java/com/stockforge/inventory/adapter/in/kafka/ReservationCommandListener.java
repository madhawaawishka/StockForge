package com.stockforge.inventory.adapter.in.kafka;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.Topics;
import com.stockforge.inventory.application.ReservationCommandHandler;
import com.stockforge.messaging.EnvelopeCodec;
import com.stockforge.messaging.IncomingMessage;
import com.stockforge.messaging.PoisonMessageException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the saga's commands to Inventory (spec §16 B). The offset is committed only after the handler's transaction
 * committed, so a crash redelivers the command and the inbox makes the redelivery a no-op (at-least-once + idempotency,
 * spec §18). Failures are retried and then dead-lettered by the shared error handler.
 */
@Component
class ReservationCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationCommandListener.class);

    private final ReservationCommandHandler handler;
    private final EnvelopeCodec codec;

    ReservationCommandListener(ReservationCommandHandler handler, EnvelopeCodec codec) {
        this.handler = handler;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "inventory-service")
    void onCommand(ConsumerRecord<String, String> record) {
        IncomingMessage message = codec.decode(record.topic(), record.value());
        boolean applied = switch (message.eventType()) {
            case InventoryMessages.ConfirmReservation.TYPE ->
                handler.confirm(message.eventId(), message.payloadAs(InventoryMessages.ConfirmReservation.class));
            case InventoryMessages.ReleaseReservation.TYPE ->
                handler.release(message.eventId(), message.payloadAs(InventoryMessages.ReleaseReservation.class));
            default -> throw new PoisonMessageException("Unknown inventory command " + message);
        };
        if (!applied) {
            log.debug("Duplicate delivery of {} ignored", message);
        }
    }
}
