package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a PENDING reservation, releasing reserved stock back to available and freeing purchase limit (spec §16 A).
 */
public class CancelReservationUseCase {

    private final ReservationRepository reservations;
    private final InventoryRepository inventories;
    private final UserPurchaseLimitRepository purchaseLimits;
    private final Clock clock;

    public CancelReservationUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.purchaseLimits = Objects.requireNonNull(purchaseLimits, "purchaseLimits must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public CancelReservationResult execute(ReservationId id) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Optional<Reservation> found = reservations.findById(id);
        if (found.isEmpty()) {
            return new CancelReservationResult.NotFound(id);
        }

        Reservation reservation = found.get();
        if (!reservation.isPending()) {
            return new CancelReservationResult.InvalidState(id, reservation.status());
        }

        // Conditional transition in database: status PENDING -> CANCELLED
        boolean updated =
                reservations.updateStatusIfCurrent(id, ReservationStatus.PENDING, ReservationStatus.CANCELLED, now);
        if (!updated) {
            return new CancelReservationResult.InvalidState(id, reservation.status());
        }

        // Return reserved stock to available
        inventories.releaseReservedStock(reservation.productId(), reservation.quantity(), now);

        // Free active purchase limit
        purchaseLimits.release(reservation.userId(), reservation.productId(), reservation.quantity(), now);

        return new CancelReservationResult.Cancelled(reservation.cancel(now));
    }
}
