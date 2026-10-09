package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
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

    public synchronized int count() {
        return inventories.size();
    }
}
