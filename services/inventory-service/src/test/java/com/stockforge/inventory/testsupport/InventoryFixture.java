package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.application.ReservationPolicy;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import java.time.Duration;
import java.time.Instant;

/**
 * All in-memory adapters of the inventory service wired together, for fast use-case tests without Spring or a database
 * (spec §29). The strategy behaves like the atomic conditional UPDATE.
 */
public final class InventoryFixture {

    public static final Instant START = Instant.parse("2026-10-08T10:15:30Z");
    public static final ReservationPolicy POLICY =
            new ReservationPolicy(Duration.ofMinutes(10), 2, Duration.ofHours(24));

    public final MutableClock clock = new MutableClock(START);
    public final InMemoryProductRepository products = new InMemoryProductRepository();
    public final InMemoryInventoryRepository inventories = new InMemoryInventoryRepository();
    public final InMemoryReservationRepository reservations = new InMemoryReservationRepository(clock);
    public final InMemoryIdempotencyKeyRepository idempotencyKeys = new InMemoryIdempotencyKeyRepository();
    public final InMemoryUserPurchaseLimitRepository purchaseLimits = new InMemoryUserPurchaseLimitRepository();
    public final InMemoryOutbox outbox = new InMemoryOutbox();
    public final InMemoryProcessedMessages inbox = new InMemoryProcessedMessages();
    public final InMemoryAuditLog auditLog = new InMemoryAuditLog();
    public final SequentialIdGenerator ids = new SequentialIdGenerator();

    public final StockReservationStrategy strategy = new StockReservationStrategy() {
        @Override
        public StrategyType type() {
            return StrategyType.ATOMIC_CONDITIONAL_UPDATE;
        }

        @Override
        public StockReservationResult reserve(Reservation reservation) {
            if (!inventories.decrementAvailableAndIncrementReserved(
                    reservation.productId(), reservation.quantity(), reservation.createdAt())) {
                return new StockReservationResult.InsufficientStock();
            }
            reservations.insert(reservation);
            return new StockReservationResult.Success();
        }
    };

    /** Creates an active product with {@code stock} available units. */
    public ProductId product(String sku, int stock) {
        ProductId id = ProductId.of(ids.newId());
        products.insertIfSkuAvailable(
                Product.create(id, Sku.of(sku), "Product " + sku, Money.of("19.99", "USD"), START));
        inventories.insert(Inventory.initial(id, stock, START));
        return id;
    }

    public Inventory inventoryOf(ProductId id) {
        return inventories.findByProductId(id).orElseThrow();
    }
}
