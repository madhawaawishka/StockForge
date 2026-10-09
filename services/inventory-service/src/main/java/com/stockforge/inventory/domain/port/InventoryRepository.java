package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import java.util.Optional;

/** Persistence port for stock levels. */
public interface InventoryRepository {

    void insert(Inventory inventory);

    Optional<Inventory> findByProductId(ProductId productId);
}
