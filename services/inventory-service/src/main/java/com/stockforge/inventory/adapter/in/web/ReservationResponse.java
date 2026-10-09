package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

record ReservationResponse(
        @Schema(description = "Reservation identifier (UUIDv7)")
        UUID id,

        @Schema(description = "User who owns the reservation")
        UUID userId,

        @Schema(description = "Product identifier") UUID productId,
        @Schema(description = "Quantity reserved") int quantity,

        @Schema(description = "Current status", example = "PENDING")
        String status,

        @Schema(description = "Expiration timestamp") Instant expiresAt,
        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.id().value(),
                reservation.userId().value(),
                reservation.productId().value(),
                reservation.quantity(),
                reservation.status().name(),
                reservation.expiresAt(),
                reservation.createdAt(),
                reservation.updatedAt());
    }
}
