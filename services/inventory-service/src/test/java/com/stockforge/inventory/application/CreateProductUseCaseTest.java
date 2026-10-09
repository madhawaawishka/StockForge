package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.inventory.domain.model.DomainValidationException;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.testsupport.InMemoryInventoryRepository;
import com.stockforge.inventory.testsupport.InMemoryProductRepository;
import com.stockforge.inventory.testsupport.SequentialIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateProductUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123456789Z");

    private final InMemoryProductRepository products = new InMemoryProductRepository();
    private final InMemoryInventoryRepository inventories = new InMemoryInventoryRepository();
    private final CreateProductUseCase useCase = new CreateProductUseCase(
            products, inventories, new SequentialIdGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAnActiveProductWithFullyAvailableInitialStock() {
        CreateProductResult result = useCase.execute(command("SNEAKER-RED-42", 100));

        assertThat(result).isInstanceOfSatisfying(CreateProductResult.Created.class, created -> {
            assertThat(created.product().id()).isEqualTo(ProductId.of(new UUID(0, 1)));
            assertThat(created.product().status()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(created.inventory().availableQuantity()).isEqualTo(100);
            assertThat(created.inventory().totalQuantity()).isEqualTo(100);
        });
        assertThat(products.count()).isOne();
        assertThat(inventories.count()).isOne();
    }

    @Test
    void truncatesTimestampsToTheMicrosecondPrecisionThatPostgresStores() {
        CreateProductResult.Created created =
                (CreateProductResult.Created) useCase.execute(command("SNEAKER-RED-42", 1));

        assertThat(created.product().createdAt()).isEqualTo(Instant.parse("2026-10-08T10:15:30.123456Z"));
        assertThat(created.inventory().updatedAt()).isEqualTo(created.product().createdAt());
    }

    @Test
    void reportsDuplicateSkuAsAnExpectedOutcomeAndWritesNothing() {
        useCase.execute(command("SNEAKER-RED-42", 100));

        CreateProductResult result = useCase.execute(command("SNEAKER-RED-42", 5));

        assertThat(result).isEqualTo(new CreateProductResult.SkuAlreadyExists(Sku.of("SNEAKER-RED-42")));
        assertThat(products.count()).isOne();
        assertThat(inventories.count()).isOne();
    }

    @Test
    void validatesEverythingBeforeWritingAnything() {
        assertThatThrownBy(() -> useCase.execute(command("SNEAKER-RED-42", -1)))
                .isInstanceOf(DomainValidationException.class);

        assertThat(products.count()).isZero();
        assertThat(inventories.count()).isZero();
    }

    private static CreateProductCommand command(String sku, int initialStock) {
        return new CreateProductCommand(Sku.of(sku), "Limited Edition Sneaker", Money.of("19.99", "USD"), initialStock);
    }
}
