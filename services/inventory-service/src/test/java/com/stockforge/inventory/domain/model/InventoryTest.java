package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InventoryTest {

    private static final ProductId PRODUCT = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");

    @Test
    void initialStockIsFullyAvailable() {
        Inventory inventory = Inventory.initial(PRODUCT, 100, NOW);

        assertThat(inventory.totalQuantity()).isEqualTo(100);
        assertThat(inventory.availableQuantity()).isEqualTo(100);
        assertThat(inventory.reservedQuantity()).isZero();
        assertThat(inventory.soldQuantity()).isZero();
        assertThat(inventory.version()).isZero();
        assertThat(inventory.isInStock()).isTrue();
    }

    @Test
    void zeroStockIsValidButNotInStock() {
        assertThat(Inventory.initial(PRODUCT, 0, NOW).isInStock()).isFalse();
    }

    @Test
    void rejectsNegativeInitialStock() {
        assertThatThrownBy(() -> Inventory.initial(PRODUCT, -1, NOW)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest(name = "total={0} available={1} reserved={2} sold={3}")
    @CsvSource({
        "10, 5, 3, 1", // units unaccounted for
        "10, 6, 3, 2", // more units than exist
        "10, -1, 6, 5", // negative bucket that still sums to total
        "-1, 0, 0, -1"
    })
    void enforcesStockConservationAndNonNegativeBuckets(int total, int available, int reserved, int sold) {
        assertThatThrownBy(() -> new Inventory(PRODUCT, total, available, reserved, sold, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conservationCheckCannotBeDefeatedByIntegerOverflow() {
        assertThatThrownBy(() ->
                        new Inventory(PRODUCT, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock conservation violated");
    }
}
