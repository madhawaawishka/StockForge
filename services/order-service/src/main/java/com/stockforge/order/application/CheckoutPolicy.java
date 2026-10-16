package com.stockforge.order.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Timing rules of the checkout saga, from configuration ({@code order.checkout.*}).
 *
 * @param paymentTimeout how long an order waits for its payment result before the saga cancels it; keep it well below
 *     the reservation TTL so the expiry race stays rare (spec §16 C)
 * @param idempotencyKeyTtl how long an order's idempotency key is remembered (spec §19)
 */
public record CheckoutPolicy(Duration paymentTimeout, Duration idempotencyKeyTtl) {

    public CheckoutPolicy {
        Objects.requireNonNull(paymentTimeout, "paymentTimeout must not be null");
        Objects.requireNonNull(idempotencyKeyTtl, "idempotencyKeyTtl must not be null");
        if (paymentTimeout.isNegative() || paymentTimeout.isZero()) {
            throw new IllegalArgumentException("paymentTimeout must be positive");
        }
        if (idempotencyKeyTtl.isNegative() || idempotencyKeyTtl.isZero()) {
            throw new IllegalArgumentException("idempotencyKeyTtl must be positive");
        }
    }
}
