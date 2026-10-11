package com.stockforge.inventory.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Background use case claiming and expiring overdue reservations with SKIP LOCKED (spec §21). */
public class ExpireReservationsUseCase {

    private final ReservationRepository reservations;
    private final InventoryRepository inventories;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ExpireReservationsUseCase(
            ReservationRepository reservations, InventoryRepository inventories, OutboxPublisher outbox, Clock clock) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public int expirePendingBatch(int batchSize) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Reservation> claimed = reservations.claimExpiredPendingBatch(now, batchSize);

        int count = 0;
        for (Reservation r : claimed) {
            boolean transitioned = reservations.updateStatusIfCurrent(
                    r.id(), ReservationStatus.PENDING, ReservationStatus.EXPIRED, now);
            if (transitioned) {
                inventories.releaseReservedStock(r.productId(), r.quantity(), now);

                DomainEvents.ReservationExpired event = new DomainEvents.ReservationExpired(
                        r.id().value(), r.productId().value(), r.quantity());

                outbox.publish(
                        "Reservation",
                        r.id().value().toString(),
                        "ReservationExpired",
                        event,
                        r.id().value().toString());

                count++;
            }
        }
        return count;
    }
}
