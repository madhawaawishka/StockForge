package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Reads one reservation, hiding other users' reservations (spec §12: 404 also for other users' reservations). */
public class GetReservationUseCase {

    private final ReservationRepository reservations;

    public GetReservationUseCase(ReservationRepository reservations) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
    }

    /**
     * Finds the reservation if the requester may see it.
     *
     * @param requester the authenticated caller, or empty for privileged callers
     */
    @Transactional(readOnly = true)
    public Optional<Reservation> execute(ReservationId id, Optional<UserId> requester) {
        return reservations.findById(id).filter(reservation -> Ownership.visibleTo(reservation, requester));
    }
}
