package com.stockforge.order.application;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.ReservationId;

/** Expected outcomes of {@link PlaceOrderUseCase}. */
public sealed interface PlaceOrderResult {

    /**
     * The order exists and its payment was requested.
     *
     * @param replayed {@code true} when an earlier request with the same idempotency key placed it
     */
    record Placed(Order order, boolean replayed) implements PlaceOrderResult {}

    /** The reservation does not exist for this user, is no longer PENDING, or has passed its expiry. */
    record ReservationNotOrderable(ReservationId reservationId, String reason) implements PlaceOrderResult {}

    /** Another order already uses this reservation (one order per reservation). */
    record ReservationAlreadyOrdered(ReservationId reservationId) implements PlaceOrderResult {}

    record IdempotencyKeyReused(String idempotencyKey) implements PlaceOrderResult {}
}
