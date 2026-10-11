package com.stockforge.payment.domain.model;

import java.util.Objects;
import java.util.UUID;

public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }
}
