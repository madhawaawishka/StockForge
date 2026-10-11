package com.stockforge.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRecordTest {

    @Test
    void validNotificationRecordCreated() {
        NotificationId id = NotificationId.of(UUID.randomUUID());
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        NotificationRecord record = new NotificationRecord(
                id,
                orderId,
                userId,
                "OrderConfirmed",
                NotificationChannel.EMAIL,
                NotificationStatus.SENT,
                "Confirmed",
                now);

        assertThat(record.id()).isEqualTo(id);
        assertThat(record.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(record.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void nullFieldThrowsNullPointerException() {
        NotificationId id = NotificationId.of(UUID.randomUUID());
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        assertThatThrownBy(() -> new NotificationRecord(
                        id, orderId, userId, "OrderConfirmed", null, NotificationStatus.SENT, "Confirmed", now))
                .isInstanceOf(NullPointerException.class);
    }
}
