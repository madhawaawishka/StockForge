package com.stockforge.inventory.application;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Many clients create the same SKU at the same instant. Exactly one may win, and the losers must get a clean "already
 * exists" outcome rather than an error — the race is resolved atomically by the database.
 */
@InventoryIntegrationTest
class CreateProductConcurrencyIT {

    private static final int CONCURRENT_CLIENTS = 16;

    @Autowired
    private CreateProductUseCase createProduct;

    @Autowired
    private JdbcClient jdbc;

    @RepeatedTest(5)
    void concurrentCreatesOfTheSameSkuProduceExactlyOneProduct() throws Exception {
        Sku sku = Sku.of(uniqueSku());
        CreateProductCommand command = new CreateProductCommand(sku, "Race", Money.of("1.00", "USD"), 10);
        CountDownLatch startingGate = new CountDownLatch(1);
        List<CreateProductResult> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS)) {
            List<Future<CreateProductResult>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
                futures.add(executor.submit(() -> {
                    startingGate.await();
                    return createProduct.execute(command);
                }));
            }
            startingGate.countDown(); // release every client at once to maximize contention
            for (Future<CreateProductResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(results)
                .filteredOn(CreateProductResult.Created.class::isInstance)
                .hasSize(1);
        assertThat(results)
                .filteredOn(CreateProductResult.SkuAlreadyExists.class::isInstance)
                .hasSize(CONCURRENT_CLIENTS - 1);
        assertThat(countRows("SELECT count(*) FROM products WHERE sku = :sku", sku))
                .isOne();
        assertThat(countRows(
                        "SELECT count(*) FROM inventory i JOIN products p ON p.id = i.product_id WHERE p.sku = :sku",
                        sku))
                .isOne();
    }

    private long countRows(String sql, Sku sku) {
        return jdbc.sql(sql).param("sku", sku.value()).query(Long.class).single();
    }
}
