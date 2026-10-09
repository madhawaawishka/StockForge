package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.testsupport.InMemoryInventoryRepository;
import com.stockforge.inventory.testsupport.InMemoryProductRepository;
import com.stockforge.inventory.testsupport.SequentialIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductCatalogQueriesTest {

    private final InMemoryProductRepository products = new InMemoryProductRepository();
    private final InMemoryInventoryRepository inventories = new InMemoryInventoryRepository();
    private final ProductCatalogQueries queries = new ProductCatalogQueries(products, inventories);

    @BeforeEach
    void createFiveProducts() {
        CreateProductUseCase create = new CreateProductUseCase(
                products,
                inventories,
                new SequentialIdGenerator(),
                Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC));
        for (int i = 1; i <= 5; i++) {
            create.execute(new CreateProductCommand(Sku.of("SKU-" + i), "Product " + i, Money.of("1.00", "USD"), i));
        }
    }

    @Test
    void pagesThroughAllProductsInIdOrderWithoutGapsOrDuplicates() {
        List<ProductId> seen = new ArrayList<>();

        ProductPage page = queries.firstPage(2);
        page.items().forEach(product -> seen.add(product.id()));
        while (page.nextAfter().isPresent()) {
            page = queries.pageAfter(page.nextAfter().get(), 2);
            page.items().forEach(product -> seen.add(product.id()));
        }

        assertThat(seen).containsExactly(id(1), id(2), id(3), id(4), id(5));
    }

    @Test
    void lastPageHasNoNextCursorEvenWhenItIsExactlyFull() {
        ProductPage page = queries.firstPage(5);

        assertThat(page.items()).hasSize(5);
        assertThat(page.nextAfter()).isEmpty();
    }

    @Test
    void nextCursorPointsAtTheLastItemOfThePage() {
        ProductPage page = queries.firstPage(3);

        assertThat(page.items()).extracting(Product::id).containsExactly(id(1), id(2), id(3));
        assertThat(page.nextAfter()).contains(id(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, ProductCatalogQueries.MAX_PAGE_SIZE + 1})
    void rejectsPageSizesOutsideTheAllowedRange(int limit) {
        assertThatThrownBy(() -> queries.firstPage(limit)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findsInventoryForExistingProductsOnly() {
        assertThat(queries.findInventory(id(3)))
                .hasValueSatisfying(
                        inventory -> assertThat(inventory.availableQuantity()).isEqualTo(3));
        assertThat(queries.findInventory(id(99))).isEmpty();
    }

    private static ProductId id(long sequence) {
        return ProductId.of(new UUID(0, sequence));
    }
}
