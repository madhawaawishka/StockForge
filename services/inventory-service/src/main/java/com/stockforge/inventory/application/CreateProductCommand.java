package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Sku;
import java.util.Objects;

/** Request to add a product to the catalog together with its initial stock. */
public record CreateProductCommand(Sku sku, String name, Money price, int initialStock) {

    public CreateProductCommand {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(price, "price must not be null");
    }
}
