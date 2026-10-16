package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.testsupport.InMemoryProductCache;
import com.stockforge.inventory.testsupport.InventoryFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Audited, idempotent stock adjustments (FR-10). */
class AdjustStockUseCaseTest {

    private static final UserId ADMIN = UserId.of(UUID.fromString("0192f5c4-0000-7000-8000-0000000000ad"));

    private InventoryFixture fixture;
    private InMemoryProductCache cache;
    private AdjustStockUseCase useCase;
    private ProductId product;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture();
        cache = new InMemoryProductCache();
        useCase = new AdjustStockUseCase(
                fixture.inventories,
                fixture.idempotencyKeys,
                fixture.auditLog,
                cache,
                fixture.clock,
                InventoryFixture.POLICY);
        product = fixture.product("SNEAKER-1", 10);
    }

    @Test
    void addsReceivedUnitsToTotalAndAvailableAndAuditsTheChange() {
        cache.putInventory(fixture.inventoryOf(product));

        AdjustStockResult result = useCase.execute(new AdjustStockCommand("adj-1", ADMIN, product, 5, "Delivery 42"));

        assertThat(result).isInstanceOfSatisfying(AdjustStockResult.Adjusted.class, adjusted -> {
            assertThat(adjusted.inventory().totalQuantity()).isEqualTo(15);
            assertThat(adjusted.inventory().availableQuantity()).isEqualTo(15);
        });
        assertThat(fixture.auditLog.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.actorId()).isEqualTo(ADMIN.toString());
            assertThat(entry.action()).isEqualTo("STOCK_ADJUSTED");
            assertThat(entry.details()).containsEntry("delta", 5).containsEntry("reason", "Delivery 42");
        });
        assertThat(cache.getInventory(product)).as("stale availability evicted").isEmpty();
    }

    @Test
    void cannotWriteOffReservedUnits() {
        fixture.inventories.decrementAvailableAndIncrementReserved(product, 8, InventoryFixture.START);

        assertThat(useCase.execute(new AdjustStockCommand("adj-1", ADMIN, product, -3, "Damaged")))
                .isEqualTo(new AdjustStockResult.InsufficientAvailableStock(product, -3));
        assertThat(fixture.inventoryOf(product).totalQuantity()).isEqualTo(10);
        assertThat(fixture.auditLog.entries()).isEmpty();
    }

    @Test
    void aRetryWithTheSameKeyDoesNotAdjustTwice() {
        useCase.execute(new AdjustStockCommand("adj-1", ADMIN, product, 5, "Delivery 42"));

        AdjustStockResult retry = useCase.execute(new AdjustStockCommand("adj-1", ADMIN, product, 5, "Delivery 42"));

        assertThat(retry)
                .isInstanceOfSatisfying(
                        AdjustStockResult.Adjusted.class,
                        adjusted -> assertThat(adjusted.replayed()).isTrue());
        assertThat(fixture.inventoryOf(product).totalQuantity()).isEqualTo(15);
        assertThat(fixture.auditLog.entries()).hasSize(1);
    }

    @Test
    void unknownProduct() {
        ProductId unknown = ProductId.of(UUID.randomUUID());

        assertThat(useCase.execute(new AdjustStockCommand("adj-1", ADMIN, unknown, 5, "Delivery")))
                .isEqualTo(new AdjustStockResult.ProductNotFound(unknown));
    }
}
