package com.stockforge.payment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Payment aggregate modeling lifecycle states: PENDING -> SUCCEEDED / FAILED / TIMED_OUT -> REFUNDED (spec §16). */
public record Payment(
        PaymentId id,
        OrderId orderId,
        UserId userId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String simulationOutcome,
        Instant createdAt,
        Instant updatedAt) {

    public Payment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
    }

    public static Payment create(
            PaymentId id,
            OrderId orderId,
            UserId userId,
            BigDecimal amount,
            String currency,
            String simulationOutcome,
            Instant now) {
        return new Payment(id, orderId, userId, amount, currency, PaymentStatus.PENDING, simulationOutcome, now, now);
    }

    public Payment succeed(Instant now) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot succeed payment in status: " + status);
        }
        return new Payment(
                id, orderId, userId, amount, currency, PaymentStatus.SUCCEEDED, simulationOutcome, createdAt, now);
    }

    public Payment fail(Instant now) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot fail payment in status: " + status);
        }
        return new Payment(
                id, orderId, userId, amount, currency, PaymentStatus.FAILED, simulationOutcome, createdAt, now);
    }

    public Payment timeOut(Instant now) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot time out payment in status: " + status);
        }
        return new Payment(
                id, orderId, userId, amount, currency, PaymentStatus.TIMED_OUT, simulationOutcome, createdAt, now);
    }

    public Payment refund(Instant now) {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Only SUCCEEDED payments can be refunded, current: " + status);
        }
        return new Payment(
                id, orderId, userId, amount, currency, PaymentStatus.REFUNDED, simulationOutcome, createdAt, now);
    }
}
