package com.stockforge.gateway.domain.model;

import java.util.Objects;

public record ReservationView(
        String id, String productId, String userId, int quantity, String status, String expiresAt) {

    public ReservationView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
