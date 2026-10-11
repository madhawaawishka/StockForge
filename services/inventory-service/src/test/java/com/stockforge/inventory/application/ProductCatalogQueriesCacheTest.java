package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductCache;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductCatalogQueriesCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));

    private ProductRepository products;
    private InventoryRepository inventories;
    private ProductCache cache;
    private ProductCatalogQueries queries;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        inventories = mock(InventoryRepository.class);
        cache = mock(ProductCache.class);
        queries = new ProductCatalogQueries(products, inventories, cache);
    }

    @Test
    void findProductReturnsFromCacheOnHitWithoutCallingRepository() {
        Product cachedProduct = new Product(
                PRODUCT_ID,
                new Sku("SKU-TEST"),
                "Cached Product",
                Money.of("99.99", "USD"),
                ProductStatus.ACTIVE,
                NOW,
                NOW);
        when(cache.getProduct(PRODUCT_ID)).thenReturn(Optional.of(cachedProduct));

        Optional<Product> result = queries.findProduct(PRODUCT_ID);

        assertThat(result).contains(cachedProduct);
        verify(products, never()).findById(PRODUCT_ID);
    }

    @Test
    void findProductQueriesRepositoryAndPopulatesCacheOnMiss() {
        Product dbProduct = new Product(
                PRODUCT_ID,
                new Sku("SKU-TEST"),
                "DB Product",
                Money.of("99.99", "USD"),
                ProductStatus.ACTIVE,
                NOW,
                NOW);
        when(cache.getProduct(PRODUCT_ID)).thenReturn(Optional.empty());
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(dbProduct));

        Optional<Product> result = queries.findProduct(PRODUCT_ID);

        assertThat(result).contains(dbProduct);
        verify(cache).putProduct(dbProduct);
    }

    @Test
    void findInventoryReturnsFromCacheOnHit() {
        Inventory cachedInventory = Inventory.initial(PRODUCT_ID, 10, NOW);
        when(cache.getInventory(PRODUCT_ID)).thenReturn(Optional.of(cachedInventory));

        Optional<Inventory> result = queries.findInventory(PRODUCT_ID);

        assertThat(result).contains(cachedInventory);
        verify(inventories, never()).findByProductId(PRODUCT_ID);
    }

    @Test
    void findInventoryQueriesRepositoryAndPopulatesCacheOnMiss() {
        Inventory dbInventory = Inventory.initial(PRODUCT_ID, 10, NOW);
        when(cache.getInventory(PRODUCT_ID)).thenReturn(Optional.empty());
        when(inventories.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(dbInventory));

        Optional<Inventory> result = queries.findInventory(PRODUCT_ID);

        assertThat(result).contains(dbInventory);
        verify(cache).putInventory(dbInventory);
    }
}
