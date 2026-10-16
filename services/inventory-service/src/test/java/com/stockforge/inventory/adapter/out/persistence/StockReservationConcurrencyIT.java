package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One concurrency test suite for every {@link StockReservationStrategy} (spec §14, §29): all requests are released at
 * once by a latch so they genuinely contend for the same inventory row, each runs in its own transaction exactly as in
 * production, and every test is repeated to catch rare interleavings.
 *
 * <ul>
 *   <li>The atomic, pessimistic and optimistic strategies never oversell, and the stock-conservation and
 *       reserved-equals-pending invariants hold afterwards.
 *   <li>The naive strategy oversells under the same contention, which is the anomaly the others prevent. The database
 *       CHECK constraint does not catch it (each write is internally consistent); the reserved-versus-reservations
 *       reconciliation does.
 * </ul>
 */
@InventoryIntegrationTest
class StockReservationConcurrencyIT {

    private static final int STOCK = 100;
    private static final int ATTEMPTS = 400;
    private static final int CLIENTS = 32;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @RepeatedTest(3)
    void atomicConditionalUpdateNeverOversells() throws Exception {
        assertNeverOversells(new AtomicConditionalUpdateReservationStrategy(inventories, reservations));
    }

    @RepeatedTest(3)
    void pessimisticLockingNeverOversells() throws Exception {
        assertNeverOversells(new PessimisticLockingReservationStrategy(inventories, reservations));
    }

    @RepeatedTest(3)
    void optimisticLockingNeverOversells() throws Exception {
        UUID product = insertProductWithStock(STOCK);

        List<StockReservationResult> results =
                reserveConcurrently(new OptimisticLockingReservationStrategy(inventories, reservations, 5), product);

        // Under this much contention some requests exhaust their retries; that is the strategy's documented trade-off.
        // What must never happen is overselling.
        long reserved = count(results, StockReservationResult.Success.class);
        assertThat(reserved).isPositive().isLessThanOrEqualTo(STOCK);
        assertInvariants(product, reserved);
    }

    @RepeatedTest(2)
    void naiveReadThenWriteOversells() throws Exception {
        UUID product = insertProductWithStock(5);

        List<StockReservationResult> results =
                reserveConcurrently(new NaiveReservationStrategy(inventories, reservations), product, 50);

        long accepted = count(results, StockReservationResult.Success.class);
        assertThat(accepted)
                .as("lost updates let more reservations through than there are units")
                .isGreaterThan(5);
        assertThat(field(product, "reserved_quantity"))
                .as("the counters stay internally consistent, so the CHECK constraint cannot notice")
                .isLessThan((int) accepted);
    }

    private void assertNeverOversells(StockReservationStrategy strategy) throws Exception {
        UUID product = insertProductWithStock(STOCK);

        List<StockReservationResult> results = reserveConcurrently(strategy, product);

        assertThat(count(results, StockReservationResult.Success.class)).isEqualTo(STOCK);
        assertThat(count(results, StockReservationResult.InsufficientStock.class))
                .isEqualTo(ATTEMPTS - STOCK);
        assertThat(field(product, "available_quantity")).isZero();
        assertInvariants(product, STOCK);
    }

    private List<StockReservationResult> reserveConcurrently(StockReservationStrategy strategy, UUID product)
            throws Exception {
        return reserveConcurrently(strategy, product, ATTEMPTS);
    }

    private List<StockReservationResult> reserveConcurrently(
            StockReservationStrategy strategy, UUID product, int attempts) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<StockReservationResult>> futures = new ArrayList<>();
        try (ExecutorService clients = Executors.newFixedThreadPool(CLIENTS)) {
            for (int i = 0; i < attempts; i++) {
                Reservation reservation = Reservation.create(
                        ReservationId.of(UUID.randomUUID()),
                        UserId.of(UUID.randomUUID()),
                        ProductId.of(product),
                        1,
                        now.plusSeconds(600),
                        now);
                futures.add(clients.submit(() -> {
                    startGate.await();
                    return transaction.execute(status -> strategy.reserve(reservation));
                }));
            }
            startGate.countDown(); // every client contends for the same row at once
            List<StockReservationResult> results = new ArrayList<>();
            for (Future<StockReservationResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /** The checks of scripts/check-invariants.sql for one product. */
    private void assertInvariants(UUID product, long expectedReserved) {
        int total = field(product, "total_quantity");
        int available = field(product, "available_quantity");
        int reserved = field(product, "reserved_quantity");
        int sold = field(product, "sold_quantity");
        assertThat(available + reserved + sold).as("stock conservation").isEqualTo(total);
        assertThat(available).isNotNegative();
        assertThat(reserved).as("reserved units").isEqualTo((int) expectedReserved);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations WHERE product_id = :id AND status = 'PENDING'")
                        .param("id", product)
                        .query(Long.class)
                        .single())
                .as("one PENDING reservation per reserved unit")
                .isEqualTo(expectedReserved);
    }

    private UUID insertProductWithStock(int stock) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, 'Concurrency Test Product', 19.99, 'USD', 'ACTIVE', :now, :now)
                        """)
                .param("id", id)
                .param("sku", uniqueSku())
                .param("now", now)
                .update();
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

    private int field(UUID product, String column) {
        return jdbc.sql("SELECT " + column + " FROM inventory WHERE product_id = :id")
                .param("id", product)
                .query(Integer.class)
                .single();
    }

    private static long count(List<StockReservationResult> results, Class<?> outcome) {
        return results.stream().filter(outcome::isInstance).count();
    }
}
