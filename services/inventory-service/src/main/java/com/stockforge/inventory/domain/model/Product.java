package com.stockforge.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Catalog product aggregate. Stock is a separate aggregate ({@link Inventory}) because it changes at a very different
 * rate and is the contended "hot row" during a flash sale.
 */
public record Product(
        ProductId id, Sku sku, String name, Money price, ProductStatus status, Instant createdAt, Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 200;

    public Product {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        name = name.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException("Name must be 1-" + MAX_NAME_LENGTH + " characters");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** Creates a new, active product. */
    public static Product create(ProductId id, Sku sku, String name, Money price, Instant now) {
        return new Product(id, sku, name, price, ProductStatus.ACTIVE, now, now);
    }
}
