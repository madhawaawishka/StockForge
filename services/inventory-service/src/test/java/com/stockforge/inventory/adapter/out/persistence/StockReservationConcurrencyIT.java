package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared concurrency test suite for all {@link StockReservationStrategy} implementations (spec §14, §29).
 *
 * <p>Validates that:
 *
 * <ul>
 *   <li>The three production strategies (atomic, pessimistic, optimistic) <strong>never oversell</strong> under high
 *       contention and strictly conserve stock.
 *   <li>The {@link NaiveReservationStrategy} <strong>oversells</strong> under the same contention, proving the
 *       lost-update race condition that motivates the project.
 * </ul>
 */
@InventoryIntegrationTest
class StockReservationConcurrencyIT {

    @Autowired
    private AtomicConditionalUpdateReservationStrategy atomicStrategy;

    @Autowired
    private PessimisticLockingReservationStrategy pessimisticStrategy;

    @Autowired
    private OptimisticLockingReservationStrategy optimisticStrategy;

    @Autowired
    private NaiveReservationStrategy naiveStrategy;

    @Autowired
    private JdbcClient jdbc;

    @RepeatedTest(3)
    void atomicConditionalUpdateNeverOversells() throws Exception {
        assertSafeStrategyNeverOversells(atomicStrategy, 10, 50);
    }

    @RepeatedTest(3)
    void pessimisticLockingNeverOversells() throws Exception {
        assertSafeStrategyNeverOversells(pessimisticStrategy, 10, 50);
    }

    @RepeatedTest(3)
    void optimisticLockingNeverOversells() throws Exception {
        int initialStock = 10;
        int threads = 30;
        UUID productId = insertProductWithStock(uniqueSku(), initialStock);

        List<StockReservationResult> results = runConcurrentReservations(optimisticStrategy, productId, threads);

        long successCount = results.stream()
                .filter(StockReservationResult.Success.class::isInstance)
                .count();

        // Optimistic locking may exhaust retries for some threads, but it must NEVER oversell:
        assertThat(successCount).isLessThanOrEqualTo(initialStock);

        // Verify stock conservation invariant in database: available + reserved + sold == total
        assertStockConservation(productId, initialStock);

        // Verify row count matches successful reservations
        long reservedRows = countReservations(productId);
        assertThat(reservedRows).isEqualTo(successCount);
    }

    @Test
    void naiveStrategyOversellsUnderContention() throws Exception {
        int initialStock = 5;
        int threads = 25;
        UUID productId = insertProductWithStock(uniqueSku(), initialStock);

        List<StockReservationResult> results = runConcurrentReservations(naiveStrategy, productId, threads);

        long successCount = results.stream()
                .filter(StockReservationResult.Success.class::isInstance)
                .count();

        long reservationCountInDb = countReservations(productId);

        // PROOF OF OVERSELLING: Naive strategy accepted more reservations than total physical stock
        assertThat(reservationCountInDb)
                .as("Naive strategy must demonstrate overselling (reservations in DB > initial stock)")
                .isGreaterThan(initialStock);
        assertThat(successCount).isGreaterThan(initialStock);
    }

    private void assertSafeStrategyNeverOversells(StockReservationStrategy strategy, int initialStock, int totalThreads)
            throws Exception {
        UUID productId = insertProductWithStock(uniqueSku(), initialStock);

        List<StockReservationResult> results = runConcurrentReservations(strategy, productId, totalThreads);

        long successCount = results.stream()
                .filter(StockReservationResult.Success.class::isInstance)
                .count();
        long insufficientCount = results.stream()
                .filter(StockReservationResult.InsufficientStock.class::isInstance)
                .count();

        // Exactly initialStock units reserved, rest rejected as insufficient stock
        assertThat(successCount).isEqualTo(initialStock);
        assertThat(insufficientCount).isEqualTo(totalThreads - initialStock);

        // Database stock state
        int available = getInventoryField(productId, "available_quantity");
        int reserved = getInventoryField(productId, "reserved_quantity");
        assertThat(available).isZero();
        assertThat(reserved).isEqualTo(initialStock);

        // Invariant holds
        assertStockConservation(productId, initialStock);

        // Exactly initialStock reservations exist
        assertThat(countReservations(productId)).isEqualTo(initialStock);
    }

    private List<StockReservationResult> runConcurrentReservations(
            StockReservationStrategy strategy, UUID productId, int threads) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<StockReservationResult> results = new ArrayList<>();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(600);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<StockReservationResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Reservation reservation = Reservation.create(
                        ReservationId.of(UUID.randomUUID()),
                        UserId.of(UUID.randomUUID()),
                        ProductId.of(productId),
                        1,
                        expiresAt,
                        now);

                futures.add(executor.submit(() -> {
                    startGate.await();
                    return strategy.reserve(reservation);
                }));
            }

            // Release all threads simultaneously to maximize contention
            startGate.countDown();

            for (Future<StockReservationResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }
        return results;
    }

    private UUID insertProductWithStock(String sku, int stock) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, 'Concurrency Test Product', 19.99, 'USD', 'ACTIVE', :now, :now)
                        """).param("id", id).param("sku", sku).param("now", now).update();

        jdbc.sql("""
                        INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity,
                                               sold_quantity, version, updated_at)
                        VALUES (:productId, :stock, :stock, 0, 0, 0, :now)
                        """)
                .param("productId", id)
                .param("stock", stock)
                .param("now", now)
                .update();

        return id;
    }

    private void assertStockConservation(UUID productId, int totalStock) {
        int total = getInventoryField(productId, "total_quantity");
        int available = getInventoryField(productId, "available_quantity");
        int reserved = getInventoryField(productId, "reserved_quantity");
        int sold = getInventoryField(productId, "sold_quantity");

        assertThat(total).isEqualTo(totalStock);
        assertThat(available + reserved + sold).isEqualTo(total);
        assertThat(available).isGreaterThanOrEqualTo(0);
        assertThat(reserved).isGreaterThanOrEqualTo(0);
        assertThat(sold).isGreaterThanOrEqualTo(0);
    }

    private int getInventoryField(UUID productId, String field) {
        Integer val = jdbc.sql("SELECT " + field + " FROM inventory WHERE product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
        return val != null ? val : 0;
    }

    private long countReservations(UUID productId) {
        Long count = jdbc.sql("SELECT count(*) FROM reservations WHERE product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .single();
        return count != null ? count : 0;
    }
}
