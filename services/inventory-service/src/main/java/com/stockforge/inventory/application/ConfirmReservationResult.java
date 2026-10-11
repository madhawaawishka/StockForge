package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;

/** Closed set of outcomes when confirming a reservation (spec §16 A). */
public sealed interface ConfirmReservationResult {

    record Confirmed(Reservation reservation) implements ConfirmReservationResult {}

    record NotFound(ReservationId reservationId) implements ConfirmReservationResult {}

    record InvalidState(ReservationId reservationId, ReservationStatus currentStatus)
            implements ConfirmReservationResult {}
}
