package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import java.util.Optional;

/** Secondary port for read-aside caching of product and approximate availability metadata (spec §17). */
public interface ProductCache {

    Optional<Product> getProduct(ProductId id);

    void putProduct(Product product);

    void evictProduct(ProductId id);

    Optional<Inventory> getInventory(ProductId id);

    void putInventory(Inventory inventory);

    void evictInventory(ProductId id);

    /** No-op implementation for testing and fallback. */
    ProductCache NO_OP = new ProductCache() {
        @Override
        public Optional<Product> getProduct(ProductId id) {
            return Optional.empty();
        }

        @Override
        public void putProduct(Product product) {}

        @Override
        public void evictProduct(ProductId id) {}

        @Override
        public Optional<Inventory> getInventory(ProductId id) {
            return Optional.empty();
        }

        @Override
        public void putInventory(Inventory inventory) {}

        @Override
        public void evictInventory(ProductId id) {}
    };
}
