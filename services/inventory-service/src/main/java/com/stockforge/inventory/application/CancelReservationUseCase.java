package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** A user abandons checkout: a PENDING reservation is cancelled and its stock released immediately (spec §3). */
public class CancelReservationUseCase {

    /** Reason recorded on the {@code ReservationReleased} event. */
    public static final String REASON_USER_CANCELLED = "USER_CANCELLED";

    private final ReservationRepository reservations;
    private final ReservationTransitions transitions;
    private final Clock clock;

    public CancelReservationUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.transitions = new ReservationTransitions(reservations, inventories, purchaseLimits, outbox);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Cancels the reservation if it is still PENDING.
     *
     * @param requester the authenticated caller; a reservation of another user is reported as not found (spec §28).
     *     Empty for privileged callers.
     */
    @Transactional
    public CancelReservationResult execute(ReservationId id, Optional<UserId> requester) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Reservation> found = reservations.findById(id);
        if (found.isEmpty() || !Ownership.visibleTo(found.get(), requester)) {
            return new CancelReservationResult.NotFound(id);
        }
        Reservation reservation = found.get();
        if (!reservation.isPending()) {
            return new CancelReservationResult.InvalidState(id, reservation.status());
        }
        if (!transitions.release(reservation, REASON_USER_CANCELLED, null, id.toString(), now)) {
            // Lost a race against confirmation or expiry: report the state that won.
            return new CancelReservationResult.InvalidState(
                    id, reservations.findById(id).orElseThrow().status());
        }
        return new CancelReservationResult.Cancelled(reservation.cancel(now));
    }
}
