package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases the stock of reservations that passed their expiry time (spec §21, NFR-8).
 *
 * <p>Safe with any number of worker instances: each claims a disjoint batch with {@code FOR UPDATE SKIP LOCKED}, and
 * the conditional PENDING -> EXPIRED transition loses cleanly against a concurrent confirmation or cancellation. Expiry
 * is decided with database time, so clock skew between instances cannot expire a reservation early. Locks are taken in
 * a global order (see {@link ReservationTransitions#expireAll}), so workers and user requests cannot deadlock.
 */
public class ExpireReservationsUseCase {

    private final ReservationRepository reservations;
    private final ReservationTransitions transitions;
    private final Clock clock;

    public ExpireReservationsUseCase(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.transitions = new ReservationTransitions(reservations, inventories, purchaseLimits, outbox);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Expires at most {@code batchSize} reservations; returns how many. */
    @Transactional
    public int expireBatch(int batchSize) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Reservation> claimed = reservations.claimExpiredPendingBatch(batchSize);
        return claimed.isEmpty() ? 0 : transitions.expireAll(claimed, now).size();
    }
}
