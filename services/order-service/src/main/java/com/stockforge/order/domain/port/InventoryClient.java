package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.ReservationId;

/** Port for interacting with the Inventory Service (spec §12, §16 B). */
public interface InventoryClient {

    enum ConfirmResult {
        CONFIRMED,
        EXPIRED_OR_INVALID,
        FAILED
    }

    ConfirmResult confirmReservation(ReservationId reservationId);

    void releaseReservation(ReservationId reservationId, String reason);
}
