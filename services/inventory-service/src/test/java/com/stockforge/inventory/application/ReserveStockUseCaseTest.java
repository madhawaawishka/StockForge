package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import com.stockforge.inventory.testsupport.InMemoryIdempotencyKeyRepository;
import com.stockforge.inventory.testsupport.InMemoryInventoryRepository;
import com.stockforge.inventory.testsupport.InMemoryProductRepository;
import com.stockforge.inventory.testsupport.InMemoryReservationRepository;
import com.stockforge.inventory.testsupport.InMemoryUserPurchaseLimitRepository;
import com.stockforge.inventory.testsupport.SequentialIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReserveStockUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));

    private InMemoryProductRepository products;
    private InMemoryInventoryRepository inventories;
    private InMemoryReservationRepository reservations;
    private InMemoryIdempotencyKeyRepository idempotencyKeys;
    private InMemoryUserPurchaseLimitRepository purchaseLimits;
    private SequentialIdGenerator ids;
    private Clock clock;
    private ReserveStockUseCase useCase;

    @BeforeEach
    void setUp() {
        products = new InMemoryProductRepository();
        inventories = new InMemoryInventoryRepository();
        reservations = new InMemoryReservationRepository();
        idempotencyKeys = new InMemoryIdempotencyKeyRepository();
        purchaseLimits = new InMemoryUserPurchaseLimitRepository();
        ids = new SequentialIdGenerator();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        StockReservationStrategy strategy = new StockReservationStrategy() {
            @Override
            public StrategyType type() {
                return StrategyType.ATOMIC_CONDITIONAL_UPDATE;
            }

            @Override
            public StockReservationResult reserve(Reservation reservation) {
                boolean decremented = inventories.decrementAvailableAndIncrementReserved(
                        reservation.productId(), reservation.quantity(), reservation.createdAt());
                if (!decremented) {
                    return new StockReservationResult.InsufficientStock();
                }
                reservations.insert(reservation);
                return new StockReservationResult.Success();
            }
        };

        useCase =
                new ReserveStockUseCase(products, strategy, reservations, idempotencyKeys, purchaseLimits, ids, clock);

        Product product =
                Product.create(PRODUCT_ID, Sku.of("TEST-SKU-1"), "Test Product", Money.of("29.99", "USD"), NOW);
        products.insertIfSkuAvailable(product);
        inventories.insert(Inventory.initial(PRODUCT_ID, 10, NOW));
    }

    @Test
    void reservesStockSuccessfully() {
        var command = new ReserveStockCommand("key-1", USER_ID, PRODUCT_ID, 2);

        ReserveStockResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ReserveStockResult.Reserved.class);
        Reservation reservation = ((ReserveStockResult.Reserved) result).reservation();
        assertThat(reservation.quantity()).isEqualTo(2);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(inventories.findByProductId(PRODUCT_ID).orElseThrow().availableQuantity())
                .isEqualTo(8);
        assertThat(inventories.findByProductId(PRODUCT_ID).orElseThrow().reservedQuantity())
                .isEqualTo(2);
    }

    @Test
    void replaysIdempotentlyWhenSameKeyAndPayload() {
        var command = new ReserveStockCommand("key-1", USER_ID, PRODUCT_ID, 1);

        ReserveStockResult first = useCase.execute(command);
        assertThat(first).isInstanceOf(ReserveStockResult.Reserved.class);

        ReserveStockResult replay = useCase.execute(command);
        assertThat(replay).isInstanceOf(ReserveStockResult.IdempotentReplay.class);
        assertThat(((ReserveStockResult.IdempotentReplay) replay).reservation().id())
                .isEqualTo(((ReserveStockResult.Reserved) first).reservation().id());
        // Available stock only decremented once
        assertThat(inventories.findByProductId(PRODUCT_ID).orElseThrow().availableQuantity())
                .isEqualTo(9);
    }

    @Test
    void rejectsReusedKeyWithDifferentPayload() {
        useCase.execute(new ReserveStockCommand("key-1", USER_ID, PRODUCT_ID, 1));

        var differentCommand = new ReserveStockCommand("key-1", USER_ID, PRODUCT_ID, 2);
        ReserveStockResult result = useCase.execute(differentCommand);

        assertThat(result)
                .isInstanceOf(ReserveStockResult.IdempotencyConflict.class)
                .extracting("message")
                .asString()
                .contains("different request payload");
    }

    @Test
    void rejectsWhenInsufficientStock() {
        ProductId scarceProduct = ProductId.of(UUID.fromString("0192f5c4-1111-7777-8888-000000000000"));
        products.insertIfSkuAvailable(
                Product.create(scarceProduct, Sku.of("SCARCE-SKU"), "Scarce", Money.of("10.00", "USD"), NOW));
        inventories.insert(Inventory.initial(scarceProduct, 1, NOW));

        var command = new ReserveStockCommand("key-scarce", USER_ID, scarceProduct, 2);

        ReserveStockResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ReserveStockResult.InsufficientStock.class);
        assertThat(reservations.count()).isZero();
        assertThat(inventories.findByProductId(scarceProduct).orElseThrow().availableQuantity())
                .isEqualTo(1);
    }

    @Test
    void rejectsWhenPurchaseLimitExceeded() {
        useCase.execute(new ReserveStockCommand("key-1", USER_ID, PRODUCT_ID, 2));

        var command = new ReserveStockCommand("key-2", USER_ID, PRODUCT_ID, 1);
        ReserveStockResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ReserveStockResult.PurchaseLimitExceeded.class);
    }

    @Test
    void rejectsWhenProductDoesNotExist() {
        ProductId unknown = ProductId.of(UUID.fromString("0192f5c4-0000-7000-8000-000000000000"));
        var command = new ReserveStockCommand("key-1", USER_ID, unknown, 1);

        ReserveStockResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ReserveStockResult.ProductNotFound.class);
    }
}
