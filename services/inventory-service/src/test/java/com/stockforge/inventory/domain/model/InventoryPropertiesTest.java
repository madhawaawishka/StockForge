package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Property-based check of the stock-conservation invariant (spec §29): whatever sequence of reserve, release, confirm
 * and adjust operations is applied, every state is valid and {@code available + reserved + sold = total}. Operations
 * the aggregate rejects (for example reserving more than is available) must leave it unchanged.
 */
class InventoryPropertiesTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");

    enum Kind {
        RESERVE,
        RELEASE,
        CONFIRM,
        ADJUST
    }

    record Operation(Kind kind, int amount) {}

    @Property(tries = 500)
    void anySequenceOfOperationsConservesStock(
            @ForAll @IntRange(min = 0, max = 50) int initialStock,
            @ForAll("operations") @Size(max = 60) List<Operation> operations) {
        Inventory inventory = Inventory.initial(ProductId.of(UUID.randomUUID()), initialStock, NOW);
        long expectedTotal = initialStock;

        for (Operation operation : operations) {
            Inventory before = inventory;
            try {
                inventory = apply(inventory, operation);
                if (operation.kind() == Kind.ADJUST) {
                    expectedTotal += operation.amount();
                }
            } catch (DomainValidationException rejected) {
                assertThat(inventory).as("a rejected operation changes nothing").isEqualTo(before);
            }

            assertThat(inventory.availableQuantity()).isNotNegative();
            assertThat(inventory.reservedQuantity()).isNotNegative();
            assertThat(inventory.soldQuantity()).isNotNegative();
            assertThat((long) inventory.availableQuantity() + inventory.reservedQuantity() + inventory.soldQuantity())
                    .isEqualTo(inventory.totalQuantity())
                    .isEqualTo(expectedTotal);
        }
    }

    @Property(tries = 200)
    void soldUnitsNeverDecrease(@ForAll("operations") @Size(max = 60) List<Operation> operations) {
        Inventory inventory = Inventory.initial(ProductId.of(UUID.randomUUID()), 20, NOW);
        for (Operation operation : operations) {
            int soldBefore = inventory.soldQuantity();
            try {
                inventory = apply(inventory, operation);
            } catch (DomainValidationException rejected) {
                // rejected operations are covered by the conservation property
            }
            assertThat(inventory.soldQuantity()).isGreaterThanOrEqualTo(soldBefore);
        }
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Operation> operation = Combinators.combine(
                        Arbitraries.of(Kind.class), Arbitraries.integers().between(-10, 10))
                .as((kind, amount) -> new Operation(kind, kind == Kind.ADJUST ? amount : Math.abs(amount)));
        return operation.list();
    }

    private static Inventory apply(Inventory inventory, Operation operation) {
        return switch (operation.kind()) {
            case RESERVE -> inventory.reserve(operation.amount(), NOW);
            case RELEASE -> inventory.releaseReserved(operation.amount(), NOW);
            case CONFIRM -> inventory.confirmReserved(operation.amount(), NOW);
            case ADJUST -> inventory.adjust(operation.amount(), NOW);
        };
    }
}
