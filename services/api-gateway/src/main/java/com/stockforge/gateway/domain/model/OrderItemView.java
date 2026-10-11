package com.stockforge.gateway.domain.model;

import java.util.Objects;

public record OrderItemView(String productId, int quantity, String unitPrice, String currency) {

    public OrderItemView {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
