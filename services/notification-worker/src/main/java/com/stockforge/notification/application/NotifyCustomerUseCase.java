package com.stockforge.notification.application;

import com.stockforge.events.OrderMessages;
import com.stockforge.notification.domain.model.Notification;
import com.stockforge.notification.domain.model.NotificationChannel;
import com.stockforge.notification.domain.model.NotificationId;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tells the customer what happened to their order (spec §3 "Notify customer", FR-7, FR-9).
 *
 * <p>Register the notification (one per event), send it with no transaction open, then mark it sent. A redelivered
 * event finds the notification: if it was sent, nothing happens (no duplicate, FR-9); if sending failed before, it is
 * sent again. Delivery is therefore at-least-once with deduplication by event id, never silently dropped.
 */
public class NotifyCustomerUseCase {

    private final NotificationTransactions transactions;
    private final NotificationSender sender;
    private final Supplier<UUID> ids;
    private final Clock clock;

    public NotifyCustomerUseCase(
            NotificationTransactions transactions, NotificationSender sender, Supplier<UUID> ids, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns {@code false} if the customer had already been notified about this event. */
    public boolean orderConfirmed(UUID eventId, OrderMessages.OrderConfirmed event) {
        return notify(
                eventId,
                event.orderId(),
                event.userId(),
                OrderMessages.OrderConfirmed.TYPE,
                "Your order " + event.orderId() + " is confirmed. Total: " + event.totalAmount() + " "
                        + event.currency() + ".");
    }

    public boolean orderCancelled(UUID eventId, OrderMessages.OrderCancelled event) {
        return notify(
                eventId,
                event.orderId(),
                event.userId(),
                OrderMessages.OrderCancelled.TYPE,
                "Your order " + event.orderId() + " was cancelled: " + event.reason()
                        + ". The reserved items were released.");
    }

    public boolean refundPending(UUID eventId, OrderMessages.OrderRefundPending event) {
        return notify(
                eventId,
                event.orderId(),
                event.userId(),
                OrderMessages.OrderRefundPending.TYPE,
                "We could not complete order " + event.orderId() + " (" + event.reason()
                        + "). Your payment is being refunded.");
    }

    public boolean refunded(UUID eventId, OrderMessages.OrderRefunded event) {
        return notify(
                eventId,
                event.orderId(),
                event.userId(),
                OrderMessages.OrderRefunded.TYPE,
                "Your payment of " + event.amount() + " " + event.currency() + " for order " + event.orderId()
                        + " was refunded.");
    }

    private boolean notify(UUID eventId, UUID orderId, UUID userId, String eventType, String message) {
        Notification notification = transactions.register(Notification.pending(
                NotificationId.of(ids.get()),
                eventId,
                orderId,
                userId,
                eventType,
                NotificationChannel.EMAIL,
                message,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        if (notification.isSent()) {
            return false;
        }
        sender.send(notification);
        transactions.markSent(notification, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return true;
    }
}
