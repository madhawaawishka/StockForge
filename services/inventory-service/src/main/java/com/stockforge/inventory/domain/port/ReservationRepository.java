package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reservations (spec §13, §16 A, §21). */
public interface ReservationRepository {

    void insert(Reservation reservation);

    Optional<Reservation> findById(ReservationId id);

    /**
     * Conditional state transition: {@code UPDATE ... WHERE id = :id AND status = :current} (spec §16 A). Exactly one
     * of several racing transitions (confirm, cancel, expire) affects the row; the others get {@code false}.
     */
    boolean updateStatusIfCurrent(
            ReservationId id, ReservationStatus currentStatus, ReservationStatus newStatus, Instant now);

    /**
     * Locks up to {@code limit} PENDING reservations whose expiry passed according to <b>database time</b>, skipping
     * rows another worker already holds ({@code FOR UPDATE SKIP LOCKED}, spec §21). Must run in a transaction.
     */
    List<Reservation> claimExpiredPendingBatch(int limit);
}
