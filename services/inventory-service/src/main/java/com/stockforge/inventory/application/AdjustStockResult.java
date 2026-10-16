package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;

/** Expected outcomes of {@link AdjustStockUseCase}. */
public sealed interface AdjustStockResult {

    /**
     * The adjustment is applied.
     *
     * @param inventory stock after the adjustment; for a replay, the current stock
     * @param replayed {@code true} if an earlier request with the same idempotency key applied the adjustment
     */
    record Adjusted(Inventory inventory, boolean replayed) implements AdjustStockResult {}

    record ProductNotFound(ProductId productId) implements AdjustStockResult {}

    /** A write-off larger than the available units; reserved and sold units cannot be written off. */
    record InsufficientAvailableStock(ProductId productId, int delta) implements AdjustStockResult {}

    record IdempotencyKeyReused(String idempotencyKey) implements AdjustStockResult {}
}
