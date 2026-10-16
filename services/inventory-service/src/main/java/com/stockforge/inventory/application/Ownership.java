package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.UserId;
import java.util.Optional;

/**
 * Object-level authorization (spec §28, OWASP API1). Enforced in the service as well as in the gateway, so a bug or a
 * bypass of one layer does not expose other users' reservations.
 */
final class Ownership {

    private Ownership() {}

    /** An empty requester is a privileged caller (administrator or internal service). */
    static boolean visibleTo(Reservation reservation, Optional<UserId> requester) {
        return requester.isEmpty() || reservation.userId().equals(requester.get());
    }
}
