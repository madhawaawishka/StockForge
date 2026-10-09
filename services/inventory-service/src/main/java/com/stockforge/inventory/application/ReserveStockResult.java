package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.UserId;

/** Closed set of expected business outcomes for {@link ReserveStockUseCase}. */
public sealed interface ReserveStockResult {

    record Reserved(Reservation reservation) implements ReserveStockResult {}

    record IdempotentReplay(Reservation reservation) implements ReserveStockResult {}

    record InsufficientStock(ProductId productId, int requestedQuantity) implements ReserveStockResult {}

    record PurchaseLimitExceeded(UserId userId, int maxLimit) implements ReserveStockResult {}

    record ProductNotFound(ProductId productId) implements ReserveStockResult {}

    record OptimisticConflict(ProductId productId) implements ReserveStockResult {}

    record IdempotencyConflict(String message) implements ReserveStockResult {}
}
