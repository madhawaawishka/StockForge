package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

record AvailabilityResponse(
        UUID productId,
        int availableQuantity,
        boolean inStock,

        @Schema(description = "When the stock level last changed")
        Instant asOf) {

    static AvailabilityResponse from(Inventory inventory) {
        return new AvailabilityResponse(
                inventory.productId().value(),
                inventory.availableQuantity(),
                inventory.isInStock(),
                inventory.updatedAt());
    }
}
