package com.stockforge.inventory;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves write skew under READ COMMITTED for per-user purchase limits and verifies the atomic fix (spec §15, W2-06).
 *
 * <p><strong>Write skew scenario:</strong> "At most 2 units per user per product". If implemented as {@code SELECT
 * SUM(quantity)} followed by {@code INSERT}, two concurrent requests both read 1 unit, both decide {@code 1 + 1 <= 2},
 * and both insert. The total reaches 3. No single row was updated twice, so row-level locks on existing rows do not
 * prevent it.
 */
@InventoryIntegrationTest
class PurchaseLimitWriteSkewIT {

    private static final int MAX_LIMIT = 2;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserPurchaseLimitRepository userPurchaseLimitRepository;

    @Test
    void uncoordinatedCheckExhibitsWriteSkew() throws Exception {
        UUID productId = insertProduct(uniqueSku());
        UUID userId = UUID.randomUUID();

        // Given: User already has 1 unit reserved (status PENDING)
        insertReservation(UUID.randomUUID(), userId, productId, 1, "PENDING");

        // When: User launches 2 concurrent reservation requests of 1 unit each
        // using the uncoordinated read-then-write approach
        CountDownLatch startGate = new CountDownLatch(1);
        int concurrentRequests = 2;

        try (ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();

                    // 1. Read current active sum without locking
                    Integer currentActive = jdbc.sql("""
                                    SELECT COALESCE(SUM(quantity), 0)
                                    FROM reservations
                                    WHERE user_id = :userId
                                      AND product_id = :productId
                                      AND status IN ('PENDING', 'CONFIRMED')
                                    """)
                            .param("userId", userId)
                            .param("productId", productId)
                            .query(Integer.class)
                            .single();

                    // 2. Both threads read 1, both check (1 + 1 <= 2) -> true
                    if (currentActive + 1 <= MAX_LIMIT) {
                        // Small gap to widen race window
                        Thread.sleep(10);
                        insertReservation(UUID.randomUUID(), userId, productId, 1, "PENDING");
                        return true;
                    }
                    return false;
                }));
            }

            startGate.countDown(); // release both threads

            for (Future<Boolean> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        // Then: REPRODUCTION OF WRITE SKEW!
        // Both requests succeeded, resulting in 3 total active units, violating the limit of 2!
        int totalActive = jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0)
                        FROM reservations
                        WHERE user_id = :userId
                          AND product_id = :productId
                          AND status IN ('PENDING', 'CONFIRMED')
                        """)
                .param("userId", userId)
                .param("productId", productId)
                .query(Integer.class)
                .single();

        assertThat(totalActive)
                .as("Uncoordinated read-then-write must demonstrate write skew exceeding MAX_LIMIT")
                .isGreaterThan(MAX_LIMIT)
                .isEqualTo(3);
    }

    @Test
    void atomicCounterFixPreventsWriteSkew() throws Exception {
        UUID productId = insertProduct(uniqueSku());
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        // Given: User already has 1 unit acquired in user_product_limits
        boolean firstAcquired =
                userPurchaseLimitRepository.tryAcquire(UserId.of(userId), ProductId.of(productId), 1, MAX_LIMIT, now);
        assertThat(firstAcquired).isTrue();

        // When: 2 concurrent requests try to acquire 1 unit each
        CountDownLatch startGate = new CountDownLatch(1);
        int concurrentRequests = 2;
        List<Boolean> acquisitionResults = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return userPurchaseLimitRepository.tryAcquire(
                            UserId.of(userId), ProductId.of(productId), 1, MAX_LIMIT, Instant.now());
                }));
            }

            startGate.countDown();

            for (Future<Boolean> f : futures) {
                acquisitionResults.add(f.get(10, TimeUnit.SECONDS));
            }
        }

        // Then: EXACTLY ONE succeeds (1 + 1 = 2) and the other is REJECTED (2 + 1 > 2)!
        long acquiredCount =
                acquisitionResults.stream().filter(Boolean::booleanValue).count();
        long rejectedCount = acquisitionResults.stream().filter(b -> !b).count();

        assertThat(acquiredCount).isOne();
        assertThat(rejectedCount).isOne();

        // Database counter is exactly 2, never exceeding MAX_LIMIT
        Integer finalActive = jdbc.sql("""
                        SELECT active_quantity
                        FROM user_product_limits
                        WHERE user_id = :userId AND product_id = :productId
                        """)
                .param("userId", userId)
                .param("productId", productId)
                .query(Integer.class)
                .single();

        assertThat(finalActive).isEqualTo(MAX_LIMIT);
    }

    private UUID insertProduct(String sku) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, 'Purchase Limit Test', 10.00, 'USD', 'ACTIVE', :now, :now)
                        """).param("id", id).param("sku", sku).param("now", now).update();
        return id;
    }

    private void insertReservation(UUID id, UUID userId, UUID productId, int quantity, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO reservations (id, user_id, product_id, quantity, status, expires_at, created_at, updated_at)
                        VALUES (:id, :userId, :productId, :quantity, :status, :expiresAt, :now, :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("status", status)
                .param("expiresAt", now.plusMinutes(10))
                .param("now", now)
                .update();
    }
}
