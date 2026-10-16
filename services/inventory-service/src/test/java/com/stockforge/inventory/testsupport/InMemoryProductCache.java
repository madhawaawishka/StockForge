package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.ProductCache;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fake cache without expiry. */
public class InMemoryProductCache implements ProductCache {

    private final Map<ProductId, Product> products = new ConcurrentHashMap<>();
    private final Map<ProductId, Inventory> inventories = new ConcurrentHashMap<>();

    @Override
    public Optional<Product> getProduct(ProductId id) {
        return Optional.ofNullable(products.get(id));
    }

    @Override
    public void putProduct(Product product) {
        products.put(product.id(), product);
    }

    @Override
    public void evictProduct(ProductId id) {
        products.remove(id);
    }

    @Override
    public Optional<Inventory> getInventory(ProductId id) {
        return Optional.ofNullable(inventories.get(id));
    }

    @Override
    public void putInventory(Inventory inventory) {
        inventories.put(inventory.productId(), inventory);
    }

    @Override
    public void evictInventory(ProductId id) {
        inventories.remove(id);
    }
}
