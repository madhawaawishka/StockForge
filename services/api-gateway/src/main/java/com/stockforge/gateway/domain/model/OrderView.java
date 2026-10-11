package com.stockforge.gateway.domain.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record OrderView(
        String id,
        String userId,
        String reservationId,
        String status,
        String totalAmount,
        String totalCurrency,
        List<OrderItemView> items,
        String createdAt) {

    public OrderView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(totalCurrency, "totalCurrency must not be null");
        items = items == null ? Collections.emptyList() : List.copyOf(items);
    }
}
