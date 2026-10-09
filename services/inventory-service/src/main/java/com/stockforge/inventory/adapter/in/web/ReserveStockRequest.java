package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.application.ReserveStockCommand;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

record ReserveStockRequest(
        @Schema(description = "Product to reserve stock for", example = "0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10") @NotNull
        UUID productId,

        @Schema(description = "User making the reservation", example = "0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f") @NotNull
        UUID userId,

        @Schema(description = "Number of units to reserve", example = "1") @Min(1)
        int quantity) {

    ReserveStockCommand toCommand(String idempotencyKey) {
        return new ReserveStockCommand(idempotencyKey, UserId.of(userId), ProductId.of(productId), quantity);
    }
}
