package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.UserId;

/**
 * Closed set of expected business outcomes of {@link ReserveStockUseCase}. Adapters map every case; the compiler
 * reports a forgotten one.
 */
public sealed interface ReserveStockResult {

    /**
     * The units are reserved.
     *
     * @param replayed {@code true} when an earlier request with the same idempotency key created it
     */
    record Reserved(Reservation reservation, boolean replayed) implements ReserveStockResult {}

    record InsufficientStock(ProductId productId, int requestedQuantity) implements ReserveStockResult {}

    record PurchaseLimitExceeded(UserId userId, int limit) implements ReserveStockResult {}

    record ProductNotFound(ProductId productId) implements ReserveStockResult {}

    /** The optimistic strategy gave up after its retries; the request may be retried with the same key. */
    record OptimisticConflict(ProductId productId) implements ReserveStockResult {}

    /** The idempotency key was already used for a different request (client misuse, spec §19). */
    record IdempotencyKeyReused(String idempotencyKey) implements ReserveStockResult {}
}
