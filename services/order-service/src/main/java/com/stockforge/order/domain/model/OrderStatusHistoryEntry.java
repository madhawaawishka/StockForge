package com.stockforge.order.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Audit entry for order state transitions (spec §13). */
public record OrderStatusHistoryEntry(OrderStatus fromStatus, OrderStatus toStatus, String reason, Instant occurredAt) {

    public OrderStatusHistoryEntry {
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
