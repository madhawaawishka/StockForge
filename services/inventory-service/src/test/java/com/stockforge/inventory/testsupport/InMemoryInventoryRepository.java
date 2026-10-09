package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Fake {@link InventoryRepository}; rejects duplicate rows like the primary key does. */
public class InMemoryInventoryRepository implements InventoryRepository {

    private final Map<ProductId, Inventory> inventories = new HashMap<>();

    @Override
    public synchronized void insert(Inventory inventory) {
        if (inventories.putIfAbsent(inventory.productId(), inventory) != null) {
            throw new IllegalStateException("Inventory already exists for " + inventory.productId());
        }
    }

    @Override
    public synchronized Optional<Inventory> findByProductId(ProductId productId) {
        return Optional.ofNullable(inventories.get(productId));
    }

    @Override
    public synchronized Optional<Inventory> findByProductIdForUpdate(ProductId productId) {
        return findByProductId(productId);
    }

    @Override
    public synchronized boolean decrementAvailableAndIncrementReserved(ProductId productId, int quantity, Instant now) {
        Inventory current = inventories.get(productId);
        if (current == null || current.availableQuantity() < quantity) {
            return false;
        }
        inventories.put(productId, current.reserve(quantity, now));
        return true;
    }

    @Override
    public synchronized boolean updateVersionedStock(
            ProductId productId, int quantity, long expectedVersion, Instant now) {
        Inventory current = inventories.get(productId);
        if (current == null || current.version() != expectedVersion || current.availableQuantity() < quantity) {
            return false;
        }
        inventories.put(productId, current.reserve(quantity, now));
        return true;
    }

    @Override
    public synchronized void updateUnconditional(ProductId productId, int newAvailable, int newReserved, Instant now) {
        Inventory current = inventories.get(productId);
        if (current != null) {
            inventories.put(
                    productId,
                    new Inventory(
                            productId,
                            current.totalQuantity(),
                            newAvailable,
                            newReserved,
                            current.soldQuantity(),
                            current.version() + 1,
                            now));
        }
    }

    @Override
    public synchronized boolean releaseReservedStock(ProductId productId, int quantity, Instant now) {
        Inventory current = inventories.get(productId);
        if (current == null || current.reservedQuantity() < quantity) {
            return false;
        }
        inventories.put(productId, current.releaseReserved(quantity, now));
        return true;
    }

    @Override
    public synchronized boolean confirmReservedStock(ProductId productId, int quantity, Instant now) {
        Inventory current = inventories.get(productId);
        if (current == null || current.reservedQuantity() < quantity) {
            return false;
        }
        inventories.put(productId, current.confirmReserved(quantity, now));
        return true;
    }

    public synchronized int count() {
        return inventories.size();
    }
}
