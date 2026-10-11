package com.stockforge.order.application;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.ReservationId;

public sealed interface CreateOrderResult {

    record Created(Order order) implements CreateOrderResult {}

    record IdempotentReplay(Order order) implements CreateOrderResult {}

    record ReservationAlreadyOrdered(ReservationId reservationId) implements CreateOrderResult {}

    record IdempotencyConflict(String message) implements CreateOrderResult {}
}
