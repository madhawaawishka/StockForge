package com.stockforge.order.application;

import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import java.util.List;
import java.util.Objects;

public record CreateOrderCommand(
        String idempotencyKey, UserId userId, ReservationId reservationId, List<OrderItem> items) {

    public CreateOrderCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}
