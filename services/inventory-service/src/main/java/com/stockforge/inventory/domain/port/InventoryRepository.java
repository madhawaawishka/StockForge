package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import java.time.Instant;
import java.util.Optional;

/**
 * Stock levels (spec §13, §14). Every mutation is a single conditional UPDATE that re-checks its precondition in the
 * database, so it cannot act on a stale read; the stock-conservation CHECK constraint backs each one up.
 */
public interface InventoryRepository {

    void insert(Inventory inventory);

    Optional<Inventory> findByProductId(ProductId productId);

    /** Reads the row with an exclusive lock ({@code SELECT ... FOR UPDATE}), for the pessimistic strategy. */
    Optional<Inventory> findByProductIdForUpdate(ProductId productId);

    /** available -= quantity, reserved += quantity, if at least {@code quantity} is available. */
    boolean decrementAvailableAndIncrementReserved(ProductId productId, int quantity, Instant now);

    /**
     * Same as {@link #decrementAvailableAndIncrementReserved} but only if the row still has {@code expectedVersion}.
     */
    boolean updateVersionedStock(ProductId productId, int quantity, long expectedVersion, Instant now);

    /**
     * Writes absolute values computed from an earlier read, without any check. Exists only for the naive strategy that
     * demonstrates the lost-update anomaly (spec §14); never use it on a real write path.
     */
    void overwriteAvailableAndReserved(ProductId productId, int available, int reserved, Instant now);

    /** reserved -= quantity, available += quantity (cancellation, expiry, saga compensation). */
    boolean releaseReservedStock(ProductId productId, int quantity, Instant now);

    /** reserved -= quantity, sold += quantity (saga confirmation). */
    boolean confirmReservedStock(ProductId productId, int quantity, Instant now);

    /** total += delta and available += delta, if available stays non-negative. Returns the new state if applied. */
    Optional<Inventory> adjustStock(ProductId productId, int delta, Instant now);
}
