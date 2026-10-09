package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.application.CreateProductCommand;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.Sku;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request body for creating a product. Kept separate from the domain model to prevent mass assignment. */
record CreateProductRequest(
        @Schema(example = "SNEAKER-RED-42")
        @NotNull
        @Pattern(
                regexp = Sku.FORMAT,
                message = "must be 3-64 characters of A-Z, 0-9 or '-', starting with a letter or digit")
        String sku,

        @Schema(example = "Limited Edition Sneaker") @NotBlank @Size(max = Product.MAX_NAME_LENGTH)
        String name,

        @NotNull @Valid MoneyDto price,

        @Schema(example = "100") @NotNull @PositiveOrZero @Max(MAX_INITIAL_STOCK)
        Integer initialStock) {

    static final int MAX_INITIAL_STOCK = 1_000_000;

    CreateProductCommand toCommand() {
        return new CreateProductCommand(Sku.of(sku), name, price.toMoney(), initialStock);
    }
}
