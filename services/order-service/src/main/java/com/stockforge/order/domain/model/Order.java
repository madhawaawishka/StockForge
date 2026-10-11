package com.stockforge.order.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Order aggregate (spec §13, §16).
 *
 * <p>Enforces state machine transitions, line item calculation, and immutability invariants in pure Java.
 */
public record Order(
        OrderId id,
        UserId userId,
        ReservationId reservationId,
        OrderStatus status,
        Money totalAmount,
        List<OrderItem> items,
        List<OrderStatusHistoryEntry> history,
        Instant createdAt,
        Instant updatedAt) {

    public Order {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(history, "history must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (items.isEmpty()) {
            throw new DomainValidationException("Order must have at least one item");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException("updatedAt must not be before createdAt");
        }
        items = Collections.unmodifiableList(new ArrayList<>(items));
        history = Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** Creates a new Order in PENDING_PAYMENT status. */
    public static Order create(
            OrderId id, UserId userId, ReservationId reservationId, List<OrderItem> items, Instant now) {
        if (items.isEmpty()) {
            throw new DomainValidationException("Order must have at least one item");
        }
        Money total = items.getFirst().lineTotal();
        for (int i = 1; i < items.size(); i++) {
            total = total.add(items.get(i).lineTotal());
        }
        List<OrderStatusHistoryEntry> initialHistory =
                List.of(new OrderStatusHistoryEntry(null, OrderStatus.PENDING_PAYMENT, "Order created", now));
        return new Order(
                id, userId, reservationId, OrderStatus.PENDING_PAYMENT, total, items, initialHistory, now, now);
    }

    /** Transitions to CONFIRMED. */
    public Order confirm(Instant now, String reason) {
        return transitionTo(OrderStatus.CONFIRMED, reason, now);
    }

    /** Transitions to CANCELLED. */
    public Order cancel(Instant now, String reason) {
        return transitionTo(OrderStatus.CANCELLED, reason, now);
    }

    /** Transitions to REFUND_PENDING (e.g. payment arrived after reservation expired). */
    public Order refundPending(Instant now, String reason) {
        return transitionTo(OrderStatus.REFUND_PENDING, reason, now);
    }

    /** Transitions from REFUND_PENDING to REFUNDED. */
    public Order refund(Instant now, String reason) {
        return transitionTo(OrderStatus.REFUNDED, reason, now);
    }

    private Order transitionTo(OrderStatus nextStatus, String reason, Instant now) {
        if (!status.canTransitionTo(nextStatus)) {
            throw new DomainValidationException(
                    "Cannot transition order from " + status + " to " + nextStatus + ": illegal transition");
        }
        List<OrderStatusHistoryEntry> newHistory = new ArrayList<>(history);
        newHistory.add(new OrderStatusHistoryEntry(status, nextStatus, reason, now));
        return new Order(id, userId, reservationId, nextStatus, totalAmount, items, newHistory, createdAt, now);
    }
}
