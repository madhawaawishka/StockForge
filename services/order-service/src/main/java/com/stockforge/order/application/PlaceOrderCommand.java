package com.stockforge.order.application;

import com.stockforge.order.domain.model.DomainValidationException;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A user turns a reservation into an order (spec §12 {@code POST /orders}).
 *
 * @param paymentSimulation optional simulated payment outcome for tests and demos (FR-6)
 */
public record PlaceOrderCommand(
        String idempotencyKey, UserId userId, ReservationId reservationId, Optional<String> paymentSimulation) {

    public static final Set<String> SIMULATED_OUTCOMES = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "NO_RESPONSE");

    private static final Pattern KEY_FORMAT = Pattern.compile("[!-~]{1,128}");

    public PlaceOrderCommand {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new DomainValidationException("Idempotency-Key must be 1-128 printable ASCII characters");
        }
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(paymentSimulation, "paymentSimulation must not be null");
        paymentSimulation.ifPresent(outcome -> {
            if (!SIMULATED_OUTCOMES.contains(outcome)) {
                throw new DomainValidationException("Payment simulation must be one of " + SIMULATED_OUTCOMES);
            }
        });
    }
}
