package com.stockforge.payment.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ProcessPaymentCommand(
        UUID orderId,
        UUID userId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String simulationOutcome) {

    public ProcessPaymentCommand {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
