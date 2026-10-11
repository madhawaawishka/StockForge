package com.stockforge.gateway.domain.model;

import java.util.Objects;

public record AvailabilityView(String productId, int availableQuantity, boolean approximate) {

    public AvailabilityView {
        Objects.requireNonNull(productId, "productId must not be null");
    }
}
