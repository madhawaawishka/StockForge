package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Retrieves a reservation by ID. */
public class GetReservationUseCase {

    private final ReservationRepository reservations;

    public GetReservationUseCase(ReservationRepository reservations) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> execute(ReservationId id) {
        return reservations.findById(id);
    }
}
