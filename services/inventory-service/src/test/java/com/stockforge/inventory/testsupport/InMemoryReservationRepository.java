package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fake {@link ReservationRepository} for unit tests. */
public class InMemoryReservationRepository implements ReservationRepository {

    private final Map<ReservationId, Reservation> reservations = new HashMap<>();
    private final Clock clock;

    public InMemoryReservationRepository() {
        this(Clock.systemUTC());
    }

    public InMemoryReservationRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void insert(Reservation reservation) {
        if (reservations.putIfAbsent(reservation.id(), reservation) != null) {
            throw new IllegalStateException("Reservation already exists: " + reservation.id());
        }
    }

    @Override
    public synchronized Optional<Reservation> findById(ReservationId id) {
        return Optional.ofNullable(reservations.get(id));
    }

    @Override
    public synchronized boolean updateStatusIfCurrent(
            ReservationId id, ReservationStatus currentStatus, ReservationStatus newStatus, Instant now) {
        Reservation current = reservations.get(id);
        if (current == null || current.status() != currentStatus) {
            return false;
        }
        reservations.put(
                id,
                new Reservation(
                        current.id(),
                        current.userId(),
                        current.productId(),
                        current.quantity(),
                        newStatus,
                        current.expiresAt(),
                        current.createdAt(),
                        now));
        return true;
    }

    /** Uses the injected clock where the real repository uses database time. */
    @Override
    public synchronized List<Reservation> claimExpiredPendingBatch(int limit) {
        Instant now = clock.instant();
        return reservations.values().stream()
                .filter(r ->
                        r.status() == ReservationStatus.PENDING && r.expiresAt().isBefore(now))
                .sorted(Comparator.comparing(Reservation::expiresAt))
                .limit(limit)
                .toList();
    }

    public synchronized int count() {
        return reservations.size();
    }
}
