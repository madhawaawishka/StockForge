package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.testsupport.InMemoryInventoryRepository;
import com.stockforge.inventory.testsupport.InMemoryReservationRepository;
import com.stockforge.inventory.testsupport.InMemoryUserPurchaseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CancelReservationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));

    private InMemoryReservationRepository reservations;
    private InMemoryInventoryRepository inventories;
    private InMemoryUserPurchaseLimitRepository purchaseLimits;
    private Clock clock;
    private CancelReservationUseCase useCase;

    @BeforeEach
    void setUp() {
        reservations = new InMemoryReservationRepository();
        inventories = new InMemoryInventoryRepository();
        purchaseLimits = new InMemoryUserPurchaseLimitRepository();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        useCase = new CancelReservationUseCase(reservations, inventories, purchaseLimits, clock);

        // Given inventory with 8 available, 2 reserved
        Inventory initial = Inventory.initial(PRODUCT_ID, 10, NOW).reserve(2, NOW);
        inventories.insert(initial);

        // Given pending reservation of 2 units
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        reservations.insert(reservation);
        purchaseLimits.tryAcquire(USER_ID, PRODUCT_ID, 2, 2, NOW);
    }

    @Test
    void cancelsPendingReservationAndReleasesStockAndLimit() {
        CancelReservationResult result = useCase.execute(RESERVATION_ID);

        assertThat(result).isInstanceOf(CancelReservationResult.Cancelled.class);
        Reservation cancelled = ((CancelReservationResult.Cancelled) result).reservation();
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);

        // Stock returned to available
        Inventory updated = inventories.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(updated.availableQuantity()).isEqualTo(10);
        assertThat(updated.reservedQuantity()).isZero();

        // Limit was released, so user can acquire again
        assertThat(purchaseLimits.tryAcquire(USER_ID, PRODUCT_ID, 2, 2, NOW)).isTrue();
    }

    @Test
    void returnsNotFoundWhenReservationDoesNotExist() {
        ReservationId unknown = ReservationId.of(UUID.fromString("0192f5c4-0000-7000-8000-000000000000"));

        CancelReservationResult result = useCase.execute(unknown);

        assertThat(result).isInstanceOf(CancelReservationResult.NotFound.class);
    }

    @Test
    void rejectsCancellationWhenAlreadyTerminal() {
        // Cancel first time
        useCase.execute(RESERVATION_ID);

        // Try cancel second time
        CancelReservationResult result = useCase.execute(RESERVATION_ID);

        assertThat(result).isInstanceOf(CancelReservationResult.InvalidState.class);
        assertThat(((CancelReservationResult.InvalidState) result).currentStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }
}
