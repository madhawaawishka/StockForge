package com.stockforge.payment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Payment aggregate (spec §16 A): one payment per order.
 *
 * <pre>
 *   PENDING ---> SUCCEEDED ---> REFUNDED
 *      |
 *      +-------> FAILED
 *      +-------> TIMED_OUT
 * </pre>
 *
 * Terminal states are final. A payment stays PENDING while the provider has not answered.
 */
public record Payment(
        PaymentId id,
        OrderId orderId,
        UserId userId,
        Money amount,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Payment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (amount.amount().signum() <= 0) {
            throw new DomainValidationException("Payment amount must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException("updatedAt must not be before createdAt");
        }
    }

    public static Payment request(PaymentId id, OrderId orderId, UserId userId, Money amount, Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.PENDING, now, now);
    }

    public Payment succeed(Instant now) {
        return transition(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, now);
    }

    public Payment fail(Instant now) {
        return transition(PaymentStatus.PENDING, PaymentStatus.FAILED, now);
    }

    public Payment timeOut(Instant now) {
        return transition(PaymentStatus.PENDING, PaymentStatus.TIMED_OUT, now);
    }

    /** Compensation (spec §16 B, step 3): only a captured payment can be refunded. */
    public Payment refund(Instant now) {
        return transition(PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, now);
    }

    private Payment transition(PaymentStatus from, PaymentStatus to, Instant now) {
        if (status != from) {
            throw new DomainValidationException(
                    "Cannot move payment " + id + " from " + status + " to " + to + "; it must be " + from);
        }
        return new Payment(id, orderId, userId, amount, to, createdAt, now);
    }
}
