package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.Sku;

/** Every expected outcome of {@link CreateProductUseCase}. Adapters must handle each case explicitly. */
public sealed interface CreateProductResult {

    record Created(Product product, Inventory inventory) implements CreateProductResult {}

    record SkuAlreadyExists(Sku sku) implements CreateProductResult {}
}
