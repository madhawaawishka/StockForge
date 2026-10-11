package com.stockforge.order.domain.model;

import java.util.Objects;

/** Line item within an order (spec §13). */
public record OrderItem(ProductId productId, int quantity, Money unitPrice) {

    public OrderItem {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (quantity <= 0) {
            throw new DomainValidationException("quantity must be positive");
        }
    }

    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }
}
