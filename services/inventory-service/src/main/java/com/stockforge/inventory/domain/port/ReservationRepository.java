package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for reservations. */
public interface ReservationRepository {

    void insert(Reservation reservation);

    Optional<Reservation> findById(ReservationId id);

    /**
     * Atomically transitions reservation status if currently in {@code currentStatus}. Returns true if updated, false
     * if not found or already in another state.
     */
    boolean updateStatusIfCurrent(
            ReservationId id, ReservationStatus currentStatus, ReservationStatus newStatus, Instant now);

    /** Returns active (PENDING or CONFIRMED) quantity for the user and product. */
    int findActiveQuantityByUserAndProduct(UserId userId, ProductId productId);

    /** Finds PENDING reservations that have passed their expires_at timestamp. */
    List<Reservation> findExpiredPending(Instant now, int limit);
}
