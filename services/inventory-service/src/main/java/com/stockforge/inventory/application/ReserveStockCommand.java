package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import java.util.Objects;

/** Command to reserve inventory stock. */
public record ReserveStockCommand(String idempotencyKey, UserId userId, ProductId productId, int quantity) {

    public ReserveStockCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
