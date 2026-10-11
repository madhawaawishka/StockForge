package com.stockforge.gateway.domain.model;

import java.util.Objects;

public record ProductView(
        String id, String sku, String name, String priceAmount, String priceCurrency, int availableQuantity) {

    public ProductView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(priceAmount, "priceAmount must not be null");
        Objects.requireNonNull(priceCurrency, "priceCurrency must not be null");
    }
}
