package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpireReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final ReservationId RES_ID =
            ReservationId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));

    private FakeReservationRepo reservationRepo;
    private FakeInventoryRepo inventoryRepo;
    private RecordingOutbox outbox;
    private ExpireReservationsUseCase useCase;

    @BeforeEach
    void setUp() {
        reservationRepo = new FakeReservationRepo();
        inventoryRepo = new FakeInventoryRepo();
        outbox = new RecordingOutbox();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        // Prepopulate inventory: total 10, available 8, reserved 2, sold 0
        Inventory initial = Inventory.initial(PRODUCT_ID, 10, NOW);
        initial = initial.reserve(2, NOW);
        inventoryRepo.insert(initial);

        // Prepopulate an expired pending reservation: quantity 2, expires at NOW - 1m
        Reservation reservation = new Reservation(
                RES_ID,
                USER_ID,
                PRODUCT_ID,
                2,
                ReservationStatus.PENDING,
                NOW.minusSeconds(60),
                NOW.minusSeconds(300),
                NOW.minusSeconds(300));
        reservationRepo.insert(reservation);

        useCase = new ExpireReservationsUseCase(reservationRepo, inventoryRepo, outbox, clock);
    }

    @Test
    void expiresPendingReservationAndRestoresStockToAvailable() {
        int expired = useCase.expirePendingBatch(10);

        assertThat(expired).isEqualTo(1);
        Reservation updated = reservationRepo.findById(RES_ID).orElseThrow();
        assertThat(updated.status()).isEqualTo(ReservationStatus.EXPIRED);

        // Stock restored: available should be back to 10, reserved to 0
        Inventory inv = inventoryRepo.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(inv.availableQuantity()).isEqualTo(10);
        assertThat(inv.reservedQuantity()).isEqualTo(0);

        // Outbox event emitted
        assertThat(outbox.published).hasSize(1);
        assertThat(outbox.published.getFirst().eventType).isEqualTo("ReservationExpired");
    }

    private static class FakeReservationRepo implements ReservationRepository {
        private final Map<ReservationId, Reservation> map = new HashMap<>();

        @Override
        public void insert(Reservation r) {
            map.put(r.id(), r);
        }

        @Override
        public Optional<Reservation> findById(ReservationId id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public boolean updateStatusIfCurrent(
                ReservationId id, ReservationStatus current, ReservationStatus next, Instant now) {
            Reservation r = map.get(id);
            if (r != null && r.status() == current) {
                map.put(
                        id,
                        new Reservation(
                                r.id(),
                                r.userId(),
                                r.productId(),
                                r.quantity(),
                                next,
                                r.expiresAt(),
                                r.createdAt(),
                                now));
                return true;
            }
            return false;
        }

        @Override
        public int findActiveQuantityByUserAndProduct(UserId userId, ProductId productId) {
            return 0;
        }

        @Override
        public List<Reservation> findExpiredPending(Instant now, int limit) {
            return claimExpiredPendingBatch(now, limit);
        }

        @Override
        public List<Reservation> claimExpiredPendingBatch(Instant now, int limit) {
            return map.values().stream()
                    .filter(r -> r.status() == ReservationStatus.PENDING
                            && r.expiresAt().isBefore(now))
                    .limit(limit)
                    .toList();
        }
    }

    private static class FakeInventoryRepo implements InventoryRepository {
        private final Map<ProductId, Inventory> map = new HashMap<>();

        @Override
        public void insert(Inventory inventory) {
            map.put(inventory.productId(), inventory);
        }

        @Override
        public Optional<Inventory> findByProductId(ProductId productId) {
            return Optional.ofNullable(map.get(productId));
        }

        @Override
        public Optional<Inventory> findByProductIdForUpdate(ProductId productId) {
            return findByProductId(productId);
        }

        @Override
        public boolean decrementAvailableAndIncrementReserved(ProductId productId, int quantity, Instant now) {
            Inventory inv = map.get(productId);
            if (inv != null && inv.availableQuantity() >= quantity) {
                map.put(productId, inv.reserve(quantity, now));
                return true;
            }
            return false;
        }

        @Override
        public boolean updateVersionedStock(ProductId productId, int quantity, long expectedVersion, Instant now) {
            return false;
        }

        @Override
        public void updateUnconditional(ProductId productId, int newAvailable, int newReserved, Instant now) {}

        @Override
        public boolean releaseReservedStock(ProductId productId, int quantity, Instant now) {
            Inventory inv = map.get(productId);
            if (inv != null) {
                map.put(productId, inv.releaseReserved(quantity, now));
                return true;
            }
            return false;
        }

        @Override
        public boolean confirmReservedStock(ProductId productId, int quantity, Instant now) {
            Inventory inv = map.get(productId);
            if (inv != null) {
                map.put(productId, inv.confirmReserved(quantity, now));
                return true;
            }
            return false;
        }
    }

    private static class RecordingOutbox implements OutboxPublisher {
        record OutboxMsg(String eventType, Object payload) {}

        final List<OutboxMsg> published = new ArrayList<>();

        @Override
        public void publish(
                String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {
            published.add(new OutboxMsg(eventType, payload));
        }
    }
}
