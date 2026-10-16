package com.stockforge.notification.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A message to a customer about their order (spec §10, FR-9). One notification per triggering event: the event id is
 * unique, which is what keeps a redelivered event from notifying the customer twice.
 */
public record Notification(
        NotificationId id,
        UUID eventId,
        UUID orderId,
        UUID userId,
        String eventType,
        NotificationChannel channel,
        NotificationStatus status,
        String message,
        Instant createdAt,
        Optional<Instant> sentAt) {

    public Notification {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(sentAt, "sentAt must not be null");
        if ((status == NotificationStatus.SENT) != sentAt.isPresent()) {
            throw new IllegalArgumentException("sentAt must be set exactly when the notification is SENT");
        }
    }

    public static Notification pending(
            NotificationId id,
            UUID eventId,
            UUID orderId,
            UUID userId,
            String eventType,
            NotificationChannel channel,
            String message,
            Instant now) {
        return new Notification(
                id,
                eventId,
                orderId,
                userId,
                eventType,
                channel,
                NotificationStatus.PENDING,
                message,
                now,
                Optional.empty());
    }

    public boolean isSent() {
        return status == NotificationStatus.SENT;
    }
}
