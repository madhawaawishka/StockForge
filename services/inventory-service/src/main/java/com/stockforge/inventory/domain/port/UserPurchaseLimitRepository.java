package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;

/**
 * Per-user counter of units held in PENDING or CONFIRMED reservations of one product (spec §15).
 *
 * <p>A counter row updated with a conditional UPSERT turns the write-skew-prone "SUM then INSERT" check into a single
 * row update that concurrent requests of the same user serialize on.
 *
 * <p>Lock order: callers always take this row before the product's inventory row, which rules out deadlocks between
 * reservation, cancellation and expiry.
 */
public interface UserPurchaseLimitRepository {

    /** Adds {@code quantity} if the result stays within {@code maxLimit}; returns {@code false} otherwise. */
    boolean tryAcquire(UserId userId, ProductId productId, int quantity, int maxLimit, Instant now);

    /** Returns units to the user's allowance (cancellation, expiry, compensation). */
    void release(UserId userId, ProductId productId, int quantity, Instant now);
}
