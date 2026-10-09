package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import java.time.Instant;
import java.util.Optional;

/** Persistence port for stock levels (spec §13, §14). */
public interface InventoryRepository {

    void insert(Inventory inventory);

    Optional<Inventory> findByProductId(ProductId productId);

    /** Reads the inventory row with an exclusive lock (SELECT ... FOR UPDATE). */
    Optional<Inventory> findByProductIdForUpdate(ProductId productId);

    /**
     * Atomically decrements available_quantity and increments reserved_quantity if available >= quantity. Returns true
     * if 1 row was updated, false if insufficient stock.
     */
    boolean decrementAvailableAndIncrementReserved(ProductId productId, int quantity, Instant now);

    /**
     * Optimistically decrements available and increments reserved if version matches and available >= quantity. Returns
     * true if version matched and row updated, false on version conflict or insufficient stock.
     */
    boolean updateVersionedStock(ProductId productId, int quantity, long expectedVersion, Instant now);

    /** Unconditional stock update (used by naive strategy to demonstrate lost-update / oversell anomaly). */
    void updateUnconditional(ProductId productId, int newAvailable, int newReserved, Instant now);

    /** Moves reserved stock back to available (on reservation cancellation or expiry). */
    boolean releaseReservedStock(ProductId productId, int quantity, Instant now);

    /** Moves reserved stock to sold (on confirmation). */
    boolean confirmReservedStock(ProductId productId, int quantity, Instant now);
}
