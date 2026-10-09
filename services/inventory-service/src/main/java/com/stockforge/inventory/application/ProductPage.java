package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of products.
 *
 * @param nextAfter id to continue after, present only when more products exist
 */
public record ProductPage(List<Product> items, Optional<ProductId> nextAfter) {

    public ProductPage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextAfter, "nextAfter must not be null");
    }
}
