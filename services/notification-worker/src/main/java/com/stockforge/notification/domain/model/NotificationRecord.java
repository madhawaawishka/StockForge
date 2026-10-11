package com.stockforge.notification.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NotificationRecord(
        NotificationId id,
        UUID orderId,
        UUID userId,
        String eventType,
        NotificationChannel channel,
        NotificationStatus status,
        String message,
        Instant createdAt) {

    public NotificationRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
