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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmReservationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));

    private InMemoryReservationRepository reservations;
    private InMemoryInventoryRepository inventories;
    private Clock clock;
    private ConfirmReservationUseCase useCase;

    @BeforeEach
    void setUp() {
        reservations = new InMemoryReservationRepository();
        inventories = new InMemoryInventoryRepository();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        useCase = new ConfirmReservationUseCase(reservations, inventories, clock);

        // Given inventory with 8 available, 2 reserved
        Inventory initial = Inventory.initial(PRODUCT_ID, 10, NOW).reserve(2, NOW);
        inventories.insert(initial);

        // Given pending reservation of 2 units
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        reservations.insert(reservation);
    }

    @Test
    void confirmsPendingReservationAndMovesStockFromReservedToSold() {
        ConfirmReservationResult result = useCase.execute(RESERVATION_ID);

        assertThat(result).isInstanceOf(ConfirmReservationResult.Confirmed.class);
        Reservation confirmed = ((ConfirmReservationResult.Confirmed) result).reservation();
        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Stock moved: reserved -> sold
        Inventory updated = inventories.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(updated.availableQuantity()).isEqualTo(8);
        assertThat(updated.reservedQuantity()).isZero();
        assertThat(updated.soldQuantity()).isEqualTo(2);
        assertThat(updated.totalQuantity()).isEqualTo(10);
    }

    @Test
    void returnsNotFoundWhenReservationDoesNotExist() {
        ReservationId unknown = ReservationId.of(UUID.fromString("0192f5c4-0000-7000-8000-000000000000"));

        ConfirmReservationResult result = useCase.execute(unknown);

        assertThat(result).isInstanceOf(ConfirmReservationResult.NotFound.class);
    }

    @Test
    void rejectsConfirmationWhenAlreadyTerminal() {
        useCase.execute(RESERVATION_ID);

        // Second confirmation attempt
        ConfirmReservationResult result = useCase.execute(RESERVATION_ID);

        assertThat(result).isInstanceOf(ConfirmReservationResult.InvalidState.class);
        assertThat(((ConfirmReservationResult.InvalidState) result).currentStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }
}
