package com.stockforge.order.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Cancels an order in PENDING_PAYMENT status and triggers compensation (spec §16). */
public class CancelOrderUseCase {

    private final OrderRepository orders;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public CancelOrderUseCase(OrderRepository orders, OutboxPublisher outbox, Clock clock) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public boolean execute(OrderId id, String reason) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            return false;
        }

        Order order = found.get();
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return false;
        }

        boolean updated =
                orders.updateStatusIfCurrent(id, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, reason, now);
        if (!updated) {
            return false;
        }

        // Emit ReleaseReservationCommand to inventory for compensation (Saga Step 1 undo)
        DomainEvents.ReleaseReservationCommand releaseCmd =
                new DomainEvents.ReleaseReservationCommand(order.reservationId().value(), reason);

        outbox.publish(
                "Order",
                order.id().value().toString(),
                "ReleaseReservationCommand",
                releaseCmd,
                order.id().value().toString());

        return true;
    }
}
