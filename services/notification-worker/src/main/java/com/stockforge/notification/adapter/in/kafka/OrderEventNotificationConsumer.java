package com.stockforge.notification.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.events.DomainEvents;
import com.stockforge.notification.application.SendNotificationCommand;
import com.stockforge.notification.application.SendNotificationUseCase;
import com.stockforge.notification.domain.model.NotificationChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes order events and sends user notifications (spec §10, §18). */
@Component
class OrderEventNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventNotificationConsumer.class);
    private static final String CONSUMER_GROUP = "notification-service-group";

    private final SendNotificationUseCase sendNotificationUseCase;
    private final InboxDeduplicator inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OrderEventNotificationConsumer(
            SendNotificationUseCase sendNotificationUseCase,
            InboxDeduplicator inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.sendNotificationUseCase = sendNotificationUseCase;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(topics = DomainEvents.TOPIC_ORDER_EVENTS, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = record.key() != null ? record.key() : UUID.randomUUID().toString();
        Instant now = clock.instant();

        boolean isNew = inbox.tryAcquire(CONSUMER_GROUP, eventId, now);
        if (!isNew) {
            log.info("Duplicate order event ignored in notification worker for key: {}", eventId);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(record.value());
            if (!root.has("orderId") || !root.has("userId")) {
                log.warn("Order event missing orderId or userId: {}", record.value());
                return;
            }

            UUID orderId = UUID.fromString(root.get("orderId").asText());
            UUID userId = UUID.fromString(root.get("userId").asText());

            if (root.has("reason")) {
                // OrderCancelled
                String reason = root.get("reason").asText();
                String message = "Your order " + orderId + " has been cancelled. Reason: " + reason;
                sendNotificationUseCase.execute(new SendNotificationCommand(
                        orderId, userId, "OrderCancelled", NotificationChannel.EMAIL, message));
            } else {
                // OrderConfirmed
                String message = "Congratulations! Your order " + orderId + " has been confirmed.";
                sendNotificationUseCase.execute(new SendNotificationCommand(
                        orderId, userId, "OrderConfirmed", NotificationChannel.EMAIL, message));
            }
        } catch (Exception e) {
            log.error("Failed handling order notification event: {}", e.getMessage(), e);
            throw new IllegalStateException("Error processing order notification event", e);
        }
    }
}
