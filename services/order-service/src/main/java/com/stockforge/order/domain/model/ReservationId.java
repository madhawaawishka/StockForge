package com.stockforge.order.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ReservationId(UUID value) {
    public ReservationId {
        Objects.requireNonNull(value, "ReservationId value must not be null");
    }

    public static ReservationId of(UUID value) {
        return new ReservationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
