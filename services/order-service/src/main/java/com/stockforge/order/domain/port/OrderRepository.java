package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orders, their items and status history (spec §13). */
public interface OrderRepository {

    /**
     * Stores a new order unless its reservation already has one ({@code UNIQUE (reservation_id)}: one order per
     * reservation, guaranteed by the database).
     *
     * @return {@code false} if the reservation was already ordered
     */
    boolean insertIfReservationUnordered(Order order);

    Optional<Order> findById(OrderId id);

    /**
     * Stores the transition from {@code before} to {@code after} only if the row is still in {@code before}'s status
     * and saga step (spec §16 A); appends the new history entries.
     *
     * @return {@code false} if another transition got there first
     */
    boolean saveTransition(Order before, Order after);

    /**
     * The caller's orders, newest first, after an optional keyset position (spec §12: cursor pagination served by the
     * {@code (user_id, created_at DESC, id)} index).
     */
    List<Order> findByUser(UserId userId, Optional<PageKey> after, int limit);

    /**
     * Locks up to {@code limit} orders still waiting for their payment after {@code timeout}, measured with database
     * time, skipping orders another instance holds ({@code FOR UPDATE SKIP LOCKED}).
     */
    List<Order> claimOrdersAwaitingPaymentLongerThan(Duration timeout, int limit);

    /** Keyset position in a user's order history. */
    record PageKey(Instant createdAt, UUID orderId) {}
}
