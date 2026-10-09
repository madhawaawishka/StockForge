package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final ProductId ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final Sku SKU = Sku.of("SNEAKER-RED-42");
    private static final Money PRICE = Money.of("19.99", "USD");
    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123456Z");

    @Test
    void newProductsAreActiveAndStampedWithCreationTime() {
        Product product = Product.create(ID, SKU, "Limited Edition Sneaker", PRICE, NOW);

        assertThat(product.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.createdAt()).isEqualTo(NOW);
        assertThat(product.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void stripsSurroundingWhitespaceFromTheName() {
        assertThat(Product.create(ID, SKU, "  Sneaker  ", PRICE, NOW).name()).isEqualTo("Sneaker");
    }

    @Test
    void rejectsBlankAndOverlongNames() {
        assertThatThrownBy(() -> Product.create(ID, SKU, "   ", PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        String tooLong = "x".repeat(Product.MAX_NAME_LENGTH + 1);
        assertThatThrownBy(() -> Product.create(ID, SKU, tooLong, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThat(Product.create(ID, SKU, "x".repeat(Product.MAX_NAME_LENGTH), PRICE, NOW))
                .isNotNull();
    }

    @Test
    void rejectsUpdateTimeBeforeCreationTime() {
        assertThatThrownBy(() -> new Product(ID, SKU, "Sneaker", PRICE, ProductStatus.ACTIVE, NOW, NOW.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
