package com.stockforge.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a product. Values are time-ordered UUIDv7s (see ADR-005), so ordering by id approximates creation order
 * and keeps primary-key index inserts local.
 */
public record ProductId(UUID value) {

    public ProductId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
