package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import java.util.Optional;

/**
 * Cache-aside store for catalog reads (spec §17). PostgreSQL stays the source of truth; the cache is optional, so every
 * method must tolerate the cache being unavailable (reads report a miss, writes are skipped) rather than fail the
 * request (NFR-9).
 */
public interface ProductCache {

    Optional<Product> getProduct(ProductId id);

    void putProduct(Product product);

    /**
     * Deletes the cached product. Inside a transaction the deletion happens only after commit, so a concurrent reader
     * cannot re-cache the old value from a transaction that is about to commit, and a rollback leaves the cache alone.
     */
    void evictProduct(ProductId id);

    Optional<Inventory> getInventory(ProductId id);

    void putInventory(Inventory inventory);

    /** Same after-commit semantics as {@link #evictProduct}. */
    void evictInventory(ProductId id);

    /** A cache that always misses, for tests and for running without Redis. */
    ProductCache NONE = new ProductCache() {
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
