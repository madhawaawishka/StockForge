package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;

/** Closed set of expected outcomes for {@link CancelReservationUseCase}. */
public sealed interface CancelReservationResult {

    record Cancelled(Reservation reservation) implements CancelReservationResult {}

    record NotFound(ReservationId id) implements CancelReservationResult {}

    record InvalidState(ReservationId id, ReservationStatus currentStatus) implements CancelReservationResult {}
}
