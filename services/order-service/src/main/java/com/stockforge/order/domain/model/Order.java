package com.stockforge.order.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Order aggregate (spec §13, §16): the order, its line items with price snapshots, its status history and the state of
 * its checkout saga.
 *
 * <p>Every transition is a method that checks the current status and saga step and returns the next state, so an
 * illegal transition cannot be expressed. Persistence repeats the same check as a conditional UPDATE, so a racing
 * duplicate message affects zero rows (spec §16 A).
 */
public record Order(
        OrderId id,
        UserId userId,
        ReservationId reservationId,
        OrderStatus status,
        SagaStep sagaStep,
        Optional<UUID> paymentId,
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
        Objects.requireNonNull(sagaStep, "sagaStep must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        items = List.copyOf(items);
        history = List.copyOf(history);
        if (items.isEmpty()) {
            throw new DomainValidationException("Order must have at least one item");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException("updatedAt must not be before createdAt");
        }
        Money sum = items.getFirst().lineTotal();
        for (int i = 1; i < items.size(); i++) {
            sum = sum.add(items.get(i).lineTotal());
        }
        if (!sum.equals(totalAmount)) {
            throw new DomainValidationException("Order total " + totalAmount + " does not match its items " + sum);
        }
    }

    /** A new order waits for its payment (saga step 2). */
    public static Order place(
            OrderId id, UserId userId, ReservationId reservationId, List<OrderItem> items, Instant now) {
        if (items.isEmpty()) {
            throw new DomainValidationException("Order must have at least one item");
        }
        Money total = items.getFirst().lineTotal();
        for (int i = 1; i < items.size(); i++) {
            total = total.add(items.get(i).lineTotal());
        }
        return new Order(
                id,
                userId,
                reservationId,
                OrderStatus.PENDING_PAYMENT,
                SagaStep.PAYMENT_REQUESTED,
                Optional.empty(),
                total,
                items,
                List.of(new OrderStatusHistoryEntry(null, OrderStatus.PENDING_PAYMENT, "Order placed", now)),
                now,
                now);
    }

    /** Payment succeeded: the saga now asks Inventory to confirm the reservation (step 4). */
    public Order paymentSucceeded(UUID paymentId, Instant now) {
        require(OrderStatus.PENDING_PAYMENT, SagaStep.PAYMENT_REQUESTED, "record a payment");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        return with(status, SagaStep.CONFIRMING_RESERVATION, Optional.of(paymentId), null, now);
    }

    /** The reservation was confirmed: checkout completed (step 5). */
    public Order confirm(Instant now) {
        require(OrderStatus.PENDING_PAYMENT, SagaStep.CONFIRMING_RESERVATION, "confirm");
        return with(OrderStatus.CONFIRMED, SagaStep.COMPLETED, paymentId, "Payment succeeded and stock confirmed", now);
    }

    /** Payment failed, timed out or never arrived: the order is cancelled and its reservation released. */
    public Order cancel(String reason, Instant now) {
        require(OrderStatus.PENDING_PAYMENT, SagaStep.PAYMENT_REQUESTED, "cancel");
        return with(OrderStatus.CANCELLED, SagaStep.COMPLETED, paymentId, reason, now);
    }

    /** Payment succeeded but the reservation was already gone (expired or cancelled): refund (spec §16 C). */
    public Order refundPending(String reason, Instant now) {
        require(OrderStatus.PENDING_PAYMENT, SagaStep.CONFIRMING_RESERVATION, "start a refund for");
        return with(OrderStatus.REFUND_PENDING, SagaStep.REFUNDING, paymentId, reason, now);
    }

    public Order refunded(Instant now) {
        require(OrderStatus.REFUND_PENDING, SagaStep.REFUNDING, "complete the refund of");
        return with(OrderStatus.REFUNDED, SagaStep.COMPLETED, paymentId, "Payment refunded", now);
    }

    /** Whether a transition from this state is allowed; used to tell duplicates from bugs. */
    public boolean isIn(OrderStatus expectedStatus, SagaStep expectedStep) {
        return status == expectedStatus && sagaStep == expectedStep;
    }

    private void require(OrderStatus expectedStatus, SagaStep expectedStep, String action) {
        if (!isIn(expectedStatus, expectedStep)) {
            throw new DomainValidationException(
                    "Cannot " + action + " order " + id + " in status " + status + " at saga step " + sagaStep);
        }
    }

    private Order with(OrderStatus nextStatus, SagaStep nextStep, Optional<UUID> payment, String reason, Instant now) {
        List<OrderStatusHistoryEntry> nextHistory = new ArrayList<>(history);
        if (nextStatus != status) {
            nextHistory.add(new OrderStatusHistoryEntry(status, nextStatus, reason, now));
        }
        return new Order(
                id,
                userId,
                reservationId,
                nextStatus,
                nextStep,
                payment,
                totalAmount,
                items,
                nextHistory,
                createdAt,
                now);
    }
}
