package com.stockforge.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Stock level of one product — the inventory source of truth (spec §13).
 *
 * <p>Invariant (stock conservation): every unit is in exactly one bucket, so {@code available + reserved + sold =
 * total} and no bucket is ever negative. The same rule is enforced by a database CHECK constraint, so a bug in either
 * layer cannot corrupt stock silently.
 *
 * <p>{@code version} supports the optimistic-locking strategy (spec §14 B); it increases on every change.
 */
public record Inventory(
        ProductId productId,
        int totalQuantity,
        int availableQuantity,
        int reservedQuantity,
        int soldQuantity,
        long version,
        Instant updatedAt) {

    public Inventory {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        requireNonNegative(totalQuantity, "totalQuantity");
        requireNonNegative(availableQuantity, "availableQuantity");
        requireNonNegative(reservedQuantity, "reservedQuantity");
        requireNonNegative(soldQuantity, "soldQuantity");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        long accountedFor = (long) availableQuantity + reservedQuantity + soldQuantity;
        if (accountedFor != totalQuantity) {
            throw new IllegalArgumentException("Stock conservation violated: available + reserved + sold ("
                    + accountedFor + ") != total (" + totalQuantity + ")");
        }
    }

    /** Stock for a newly created product: everything is available. */
    public static Inventory initial(ProductId productId, int quantity, Instant now) {
        if (quantity < 0) {
            throw new DomainValidationException("Initial stock must not be negative");
        }
        return new Inventory(productId, quantity, quantity, 0, 0, 0, now);
    }

    public boolean isInStock() {
        return availableQuantity > 0;
    }

    private static void requireNonNegative(int quantity, String name) {
        if (quantity < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
