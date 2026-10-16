package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.ProductRepository;
import com.stockforge.inventory.testsupport.InMemoryInventoryRepository;
import com.stockforge.inventory.testsupport.InMemoryProductCache;
import com.stockforge.inventory.testsupport.InMemoryProductRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Cache-aside behaviour and stampede protection of catalog reads (spec §17). */
class ProductCatalogQueriesCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final Product PRODUCT =
            Product.create(PRODUCT_ID, Sku.of("SKU-TEST"), "Product", Money.of("99.99", "USD"), NOW);

    private final InMemoryProductCache cache = new InMemoryProductCache();
    private final InMemoryInventoryRepository inventories = new InMemoryInventoryRepository();

    @Test
    void aMissLoadsFromTheDatabaseAndFillsTheCacheAndAHitDoesNotTouchTheDatabase() {
        CountingProductRepository products = new CountingProductRepository(null);
        products.insertIfSkuAvailable(PRODUCT);
        ProductCatalogQueries queries = new ProductCatalogQueries(products, inventories, cache);

        assertThat(queries.findProduct(PRODUCT_ID)).contains(PRODUCT);
        assertThat(queries.findProduct(PRODUCT_ID)).contains(PRODUCT);

        assertThat(products.loads.get()).isOne();
        assertThat(cache.getProduct(PRODUCT_ID)).contains(PRODUCT);
    }

    @Test
    void availabilityFromTheCacheIsLabelledApproximate() {
        inventories.insert(Inventory.initial(PRODUCT_ID, 10, NOW));
        ProductCatalogQueries queries = new ProductCatalogQueries(new InMemoryProductRepository(), inventories, cache);

        Availability first = queries.findAvailability(PRODUCT_ID).orElseThrow();
        Availability second = queries.findAvailability(PRODUCT_ID).orElseThrow();

        assertThat(first.approximate()).isFalse();
        assertThat(second.approximate()).isTrue();
        assertThat(second.inventory()).isEqualTo(first.inventory());
    }

    @Test
    void concurrentMissesOnAColdKeyLoadTheProductOnlyOnce() throws Exception {
        CountDownLatch databaseResponds = new CountDownLatch(1);
        CountingProductRepository products = new CountingProductRepository(databaseResponds);
        products.insertIfSkuAvailable(PRODUCT);
        // A long coalescing wait so the test controls exactly when the single load completes.
        ProductCatalogQueries queries = new ProductCatalogQueries(products, inventories, cache, Duration.ofSeconds(30));

        int requests = 50;
        ConcurrentLinkedQueue<Optional<Product>> results = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            threads.add(Thread.ofPlatform().start(() -> results.add(queries.findProduct(PRODUCT_ID))));
        }

        // One request is loading (blocked in the database); every other one must be parked waiting for that load
        // instead of querying the database itself.
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> products.loads.get() == 1
                        && threads.stream()
                                        .filter(t -> t.getState() == Thread.State.TIMED_WAITING)
                                        .count()
                                == requests);
        databaseResponds.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertThat(products.loads.get()).isOne();
        assertThat(results).hasSize(requests).allMatch(result -> result.equals(Optional.of(PRODUCT)));
    }

    /** Counts database loads; optionally blocks each load until the test lets it finish. */
    private static final class CountingProductRepository implements ProductRepository {

        private final InMemoryProductRepository delegate = new InMemoryProductRepository();
        private final AtomicInteger loads = new AtomicInteger();
        private final CountDownLatch gate;

        CountingProductRepository(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public boolean insertIfSkuAvailable(Product product) {
            return delegate.insertIfSkuAvailable(product);
        }

        @Override
        public Optional<Product> findById(ProductId id) {
            loads.incrementAndGet();
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.findById(id);
        }

        @Override
        public List<Product> findFirst(int limit) {
            return delegate.findFirst(limit);
        }

        @Override
        public List<Product> findAfter(ProductId after, int limit) {
            return delegate.findAfter(after, limit);
        }
    }
}
