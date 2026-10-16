package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.DomainValidationException;
import com.stockforge.inventory.domain.model.IdempotencyKeys;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import java.util.Objects;

/** A user asks to reserve units of one product (one SKU per reservation, spec §13). */
public record ReserveStockCommand(String idempotencyKey, UserId userId, ProductId productId, int quantity) {

    public ReserveStockCommand {
        IdempotencyKeys.requireValid(idempotencyKey);
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (quantity <= 0) {
            throw new DomainValidationException("Quantity must be positive");
        }
    }
}
