package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Confirms a PENDING reservation, moving stock from reserved to sold (spec §16 A, B). */
public class ConfirmReservationUseCase {

    private final ReservationRepository reservations;
    private final InventoryRepository inventories;
    private final Clock clock;

    public ConfirmReservationUseCase(ReservationRepository reservations, InventoryRepository inventories, Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ConfirmReservationResult execute(ReservationId id) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Optional<Reservation> found = reservations.findById(id);
        if (found.isEmpty()) {
            return new ConfirmReservationResult.NotFound(id);
        }

        Reservation reservation = found.get();
        if (!reservation.isPending()) {
            return new ConfirmReservationResult.InvalidState(id, reservation.status());
        }

        // Conditional transition in database: status PENDING -> CONFIRMED
        boolean updated =
                reservations.updateStatusIfCurrent(id, ReservationStatus.PENDING, ReservationStatus.CONFIRMED, now);
        if (!updated) {
            return new ConfirmReservationResult.InvalidState(id, reservation.status());
        }

        // Move stock: reserved -> sold
        inventories.confirmReservedStock(reservation.productId(), reservation.quantity(), now);

        return new ConfirmReservationResult.Confirmed(reservation.confirm(now));
    }
}
