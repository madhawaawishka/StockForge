package com.stockforge.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.events.OrderMessages;
import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.model.NotificationId;
import com.stockforge.notification.domain.model.NotificationStatus;
import com.stockforge.notification.domain.port.NotificationRepository;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-9: duplicate events never produce duplicate notifications, and failed deliveries are retried, not lost. */
class NotifyCustomerUseCaseTest {

    private final Notifications notifications = new Notifications();
    private final FlakySender sender = new FlakySender();
    private final NotifyCustomerUseCase useCase = new NotifyCustomerUseCase(
            new NotificationTransactions(notifications),
            sender,
            UUID::randomUUID,
            Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC));

    private final OrderMessages.OrderConfirmed confirmed =
            new OrderMessages.OrderConfirmed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "39.98", "USD");

    @Test
    void sendsOneNotificationPerEventEvenWhenTheEventIsRedelivered() {
        UUID eventId = UUID.randomUUID();

        assertThat(useCase.orderConfirmed(eventId, confirmed)).isTrue();
        assertThat(useCase.orderConfirmed(eventId, confirmed)).isFalse();

        assertThat(sender.delivered).hasSize(1);
        assertThat(sender.delivered.getFirst().message()).contains("confirmed").contains("39.98 USD");
        assertThat(notifications.rows.values())
                .singleElement()
                .satisfies(n -> assertThat(n.status()).isEqualTo(NotificationStatus.SENT));
    }

    @Test
    void aFailedDeliveryIsRetriedOnRedeliveryInsteadOfBeingLost() {
        UUID eventId = UUID.randomUUID();
        sender.failNext = true;

        assertThatThrownBy(() -> useCase.orderConfirmed(eventId, confirmed)).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.rows.values())
                .singleElement()
                .satisfies(n -> assertThat(n.status()).isEqualTo(NotificationStatus.PENDING));

        assertThat(useCase.orderConfirmed(eventId, confirmed)).isTrue();
        assertThat(sender.delivered).hasSize(1);
    }

    @Test
    void cancellationsExplainWhy() {
        useCase.orderCancelled(
                UUID.randomUUID(),
                new OrderMessages.OrderCancelled(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Payment timed out"));

        assertThat(sender.delivered.getFirst().message()).contains("cancelled").contains("Payment timed out");
    }

    private static final class FlakySender implements NotificationSender {
        final List<Notification> delivered = new ArrayList<>();
        boolean failNext;

        @Override
        public void send(Notification notification) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("mail server unavailable");
            }
            delivered.add(notification);
        }
    }

    private static final class Notifications implements NotificationRepository {
        final Map<UUID, Notification> rows = new HashMap<>();

        @Override
        public Notification registerOnce(Notification notification) {
            return rows.computeIfAbsent(notification.eventId(), id -> notification);
        }

        @Override
        public void markSent(NotificationId id, Instant sentAt) {
            rows.replaceAll((eventId, n) -> n.id().equals(id)
                    ? new Notification(
                            n.id(),
                            n.eventId(),
                            n.orderId(),
                            n.userId(),
                            n.eventType(),
                            n.channel(),
                            NotificationStatus.SENT,
                            n.message(),
                            n.createdAt(),
                            Optional.of(sentAt))
                    : n);
        }
    }
}
