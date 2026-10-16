package com.stockforge.inventory.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Business limits of the reservation flow. Values come from configuration ({@code inventory.reservation.*}), never from
 * constants in code (spec §9 F).
 *
 * @param reservationTtl how long a PENDING reservation holds stock; well above the saga payment timeout (spec §16 C)
 * @param purchaseLimit maximum units one user may hold of one product in PENDING or CONFIRMED reservations (FR-4)
 * @param idempotencyKeyTtl how long an idempotency key is remembered (spec §19)
 */
public record ReservationPolicy(Duration reservationTtl, int purchaseLimit, Duration idempotencyKeyTtl) {

    public ReservationPolicy {
        Objects.requireNonNull(reservationTtl, "reservationTtl must not be null");
        Objects.requireNonNull(idempotencyKeyTtl, "idempotencyKeyTtl must not be null");
        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
        if (idempotencyKeyTtl.isNegative() || idempotencyKeyTtl.isZero()) {
            throw new IllegalArgumentException("idempotencyKeyTtl must be positive");
        }
        if (purchaseLimit < 1) {
            throw new IllegalArgumentException("purchaseLimit must be positive");
        }
    }
}
