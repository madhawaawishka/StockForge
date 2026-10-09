package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Monetary amount on the wire. The amount is a decimal string, not a JSON number, so clients that parse numbers as
 * binary floating point (such as JavaScript) cannot corrupt prices.
 */
record MoneyDto(
        @Schema(example = "19.99")
        @NotNull
        @Pattern(regexp = "\\d{1,15}(\\.\\d{1,4})?", message = "must be a decimal string such as 19.99")
        String amount,

        @Schema(example = "USD")
        @NotNull
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as USD")
        String currency) {

    static MoneyDto from(Money money) {
        return new MoneyDto(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }

    Money toMoney() {
        return Money.of(amount, currency);
    }
}
