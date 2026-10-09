package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

record ProductResponse(
        UUID id,
        String sku,
        String name,
        MoneyDto price,
        @Schema(allowableValues = {"ACTIVE", "INACTIVE"}) String status,
        Instant createdAt,
        Instant updatedAt) {

    static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id().value(),
                product.sku().value(),
                product.name(),
                MoneyDto.from(product.price()),
                product.status().name(),
                product.createdAt(),
                product.updatedAt());
    }
}
