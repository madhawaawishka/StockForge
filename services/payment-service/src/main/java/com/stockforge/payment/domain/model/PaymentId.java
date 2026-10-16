package com.stockforge.payment.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
