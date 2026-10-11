package com.stockforge.order.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.port.InventoryClient;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrated checkout saga step handler (spec §16 B, §16 C). Handles payment outcomes, compensation on failure,
 * payment-after-expiry, and saga timeouts.
 */
public class ProcessPaymentOutcomeUseCase {

    private final OrderRepository orders;
    private final InventoryClient inventoryClient;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ProcessPaymentOutcomeUseCase(
            OrderRepository orders, InventoryClient inventoryClient, OutboxPublisher outbox, Clock clock) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.inventoryClient = Objects.requireNonNull(inventoryClient, "inventoryClient must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public void handlePaymentSuccess(UUID orderId, UUID paymentId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Order> found = orders.findById(OrderId.of(orderId));
        if (found.isEmpty()) {
            return;
        }

        Order order = found.get();
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return; // Idempotent: already transitioned
        }

        InventoryClient.ConfirmResult confirmResult = inventoryClient.confirmReservation(order.reservationId());

        switch (confirmResult) {
            case CONFIRMED -> {
                boolean updated = orders.updateStatusIfCurrent(
                        order.id(),
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.CONFIRMED,
                        "Payment succeeded and reservation confirmed",
                        now);
                if (updated) {
                    DomainEvents.OrderConfirmed event = new DomainEvents.OrderConfirmed(
                            order.id().value(),
                            order.userId().value(),
                            order.reservationId().value());
                    outbox.publish(
                            "Order",
                            order.id().value().toString(),
                            "OrderConfirmed",
                            event,
                            order.id().value().toString());
                }
            }
            case EXPIRED_OR_INVALID -> {
                // W4-07: Payment arrived after reservation expired!
                boolean updated = orders.updateStatusIfCurrent(
                        order.id(),
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.REFUND_PENDING,
                        "Payment succeeded but reservation already expired",
                        now);
                if (updated) {
                    DomainEvents.RefundPaymentCommand refundCmd = new DomainEvents.RefundPaymentCommand(
                            order.id().value(), "Reservation expired before payment confirmed");
                    outbox.publish(
                            "Payment",
                            order.id().value().toString(),
                            "RefundPaymentCommand",
                            refundCmd,
                            order.id().value().toString());

                    DomainEvents.OrderCancelled cancelledEvent = new DomainEvents.OrderCancelled(
                            order.id().value(),
                            order.userId().value(),
                            order.reservationId().value(),
                            "Reservation expired before payment confirmed");
                    outbox.publish(
                            "Order",
                            order.id().value().toString(),
                            "OrderCancelled",
                            cancelledEvent,
                            order.id().value().toString());
                }
            }
            case FAILED ->
                throw new IllegalStateException(
                        "Failed communicating with Inventory Service during reservation confirmation for order "
                                + orderId);
        }
    }

    @Transactional
    public void handlePaymentFailure(UUID orderId, String reason) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Order> found = orders.findById(OrderId.of(orderId));
        if (found.isEmpty()) {
            return;
        }

        Order order = found.get();
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return;
        }

        inventoryClient.releaseReservation(order.reservationId(), "Payment failed: " + reason);

        boolean updated = orders.updateStatusIfCurrent(
                order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "Payment failed: " + reason, now);
        if (updated) {
            DomainEvents.OrderCancelled event = new DomainEvents.OrderCancelled(
                    order.id().value(),
                    order.userId().value(),
                    order.reservationId().value(),
                    "Payment failed: " + reason);
            outbox.publish(
                    "Order",
                    order.id().value().toString(),
                    "OrderCancelled",
                    event,
                    order.id().value().toString());
        }
    }

    @Transactional
    public void handlePaymentTimedOut(UUID orderId) {
        handlePaymentFailure(orderId, "Payment timed out");
    }

    @Transactional
    public void handlePaymentRefunded(UUID orderId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Order> found = orders.findById(OrderId.of(orderId));
        if (found.isEmpty()) {
            return;
        }

        Order order = found.get();
        if (order.status() == OrderStatus.REFUND_PENDING) {
            orders.updateStatusIfCurrent(
                    order.id(), OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED, "Payment refund confirmed", now);
        }
    }

    @Transactional
    public int handleSagaTimeout(Instant createdBefore, int limit) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Order> stuck = orders.findStuckPendingOrders(createdBefore, limit);

        for (Order order : stuck) {
            inventoryClient.releaseReservation(order.reservationId(), "Saga timeout - payment not completed in time");
            boolean updated = orders.updateStatusIfCurrent(
                    order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "Saga timeout", now);
            if (updated) {
                DomainEvents.OrderCancelled event = new DomainEvents.OrderCancelled(
                        order.id().value(),
                        order.userId().value(),
                        order.reservationId().value(),
                        "Saga timeout");
                outbox.publish(
                        "Order",
                        order.id().value().toString(),
                        "OrderCancelled",
                        event,
                        order.id().value().toString());
            }
        }
        return stuck.size();
    }
}
