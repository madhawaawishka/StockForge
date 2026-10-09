package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Read-only catalog and availability queries. */
@Transactional(readOnly = true)
public class ProductCatalogQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;
    private final InventoryRepository inventories;

    public ProductCatalogQueries(ProductRepository products, InventoryRepository inventories) {
        this.products = products;
        this.inventories = inventories;
    }

    public Optional<Product> findProduct(ProductId id) {
        return products.findById(id);
    }

    public ProductPage firstPage(int limit) {
        requireValidLimit(limit);
        // Fetch one extra row to learn whether another page exists without a COUNT query.
        return toPage(products.findFirst(limit + 1), limit);
    }

    public ProductPage pageAfter(ProductId after, int limit) {
        requireValidLimit(limit);
        return toPage(products.findAfter(after, limit + 1), limit);
    }

    /**
     * Current stock straight from the source of truth. Product pages may later serve a cached, approximate value (spec
     * §17); the reservation path never does.
     */
    public Optional<Inventory> findInventory(ProductId id) {
        return inventories.findByProductId(id);
    }

    private static ProductPage toPage(List<Product> rows, int limit) {
        if (rows.size() <= limit) {
            return new ProductPage(rows, Optional.empty());
        }
        List<Product> page = rows.subList(0, limit);
        return new ProductPage(page, Optional.of(page.getLast().id()));
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
