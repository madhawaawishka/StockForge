package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;

/** Port for tracking per-user active purchases and preventing write skew (spec §15). */
public interface UserPurchaseLimitRepository {

    /**
     * Atomically increments the active quantity for (userId, productId) if it would not exceed maxLimit. Returns true
     * if acquired within limit, false if limit exceeded.
     */
    boolean tryAcquire(UserId userId, ProductId productId, int quantity, int maxLimit, Instant now);

    /** Decrements the active quantity on cancellation or expiry. */
    void release(UserId userId, ProductId productId, int quantity, Instant now);
}
