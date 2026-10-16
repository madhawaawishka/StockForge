package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductCache;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Catalog and availability reads (FR-1, FR-2), cache-aside in front of PostgreSQL (spec §17).
 *
 * <p><b>Stampede protection.</b> When a hot product's cache entry is missing (cold start, eviction, expiry) thousands
 * of requests miss at once. Only the first request per product loads it; concurrent requests on this instance wait for
 * that single load (request coalescing). A waiter that times out reads the database itself rather than failing.
 *
 * <p>No transaction here on purpose: each read is one statement, and an open read-only transaction would pin a pooled
 * connection for every request waiting on a coalesced load.
 */
public class ProductCatalogQueries {

    public static final int MAX_PAGE_SIZE = 100;

    /** How long a coalesced request waits for the loader before reading the database itself. */
    static final Duration DEFAULT_COALESCE_WAIT = Duration.ofMillis(500);

    private final ProductRepository products;
    private final InventoryRepository inventories;
    private final ProductCache cache;
    private final Duration coalesceWait;
    private final ConcurrentMap<ProductId, CompletableFuture<Optional<Product>>> productLoads =
            new ConcurrentHashMap<>();

    public ProductCatalogQueries(ProductRepository products, InventoryRepository inventories, ProductCache cache) {
        this(products, inventories, cache, DEFAULT_COALESCE_WAIT);
    }

    ProductCatalogQueries(
            ProductRepository products, InventoryRepository inventories, ProductCache cache, Duration coalesceWait) {
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.coalesceWait = Objects.requireNonNull(coalesceWait, "coalesceWait must not be null");
    }

    public Optional<Product> findProduct(ProductId id) {
        Optional<Product> cached = cache.getProduct(id);
        if (cached.isPresent()) {
            return cached;
        }
        CompletableFuture<Optional<Product>> load = new CompletableFuture<>();
        CompletableFuture<Optional<Product>> inFlight = productLoads.putIfAbsent(id, load);
        if (inFlight != null) {
            return awaitLoad(id, inFlight);
        }
        try {
            Optional<Product> found = products.findById(id);
            found.ifPresent(cache::putProduct);
            load.complete(found);
            return found;
        } catch (RuntimeException failure) {
            load.completeExceptionally(failure);
            throw failure;
        } finally {
            productLoads.remove(id, load);
        }
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

    /** Availability for product pages: a seconds-old cached value is acceptable and labelled approximate. */
    public Optional<Availability> findAvailability(ProductId id) {
        Optional<Inventory> cached = cache.getInventory(id);
        if (cached.isPresent()) {
            return Optional.of(new Availability(cached.get(), true));
        }
        Optional<Inventory> found = inventories.findByProductId(id);
        found.ifPresent(cache::putInventory);
        return found.map(inventory -> new Availability(inventory, false));
    }

    private Optional<Product> awaitLoad(ProductId id, CompletableFuture<Optional<Product>> inFlight) {
        try {
            return inFlight.get(coalesceWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return products.findById(id);
        } catch (ExecutionException | TimeoutException loaderFailedOrSlow) {
            return products.findById(id);
        }
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
