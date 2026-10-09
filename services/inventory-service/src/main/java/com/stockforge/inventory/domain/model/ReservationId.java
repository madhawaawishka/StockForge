package com.stockforge.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identity of a stock reservation. Generated as a time-ordered UUIDv7 (ADR-005). */
public record ReservationId(UUID value) {

    public ReservationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ReservationId of(UUID value) {
        return new ReservationId(value);
    }

    public static ReservationId of(String value) {
        return new ReservationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
