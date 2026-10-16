package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.InventoryMessages;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.testsupport.InventoryFixture;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every way a PENDING reservation can end (spec §16 A): user cancellation, saga confirmation, saga compensation and
 * expiry. Each must move stock, the purchase-limit counter and the outbox consistently, and lose cleanly when another
 * transition got there first.
 */
class ReservationLifecycleTest {

    private static final UserId ALICE = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final UserId MALLORY = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e99"));
    private static final UUID ORDER = UUID.fromString("0192f5c4-0000-7000-8000-00000000000a");

    private InventoryFixture fixture;
    private ProductId product;
    private CancelReservationUseCase cancel;
    private ReservationCommandHandler saga;
    private ExpireReservationsUseCase expiry;
    private ReserveStockUseCase reserve;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture();
        product = fixture.product("SNEAKER-1", 10);
        reserve = new ReserveStockUseCase(
                fixture.products,
                fixture.strategy,
                fixture.reservations,
                fixture.idempotencyKeys,
                fixture.purchaseLimits,
                fixture.outbox,
                fixture.ids,
                fixture.clock,
                InventoryFixture.POLICY);
        cancel = new CancelReservationUseCase(
                fixture.reservations, fixture.inventories, fixture.purchaseLimits, fixture.outbox, fixture.clock);
        saga = new ReservationCommandHandler(
                fixture.inbox,
                fixture.reservations,
                fixture.inventories,
                fixture.purchaseLimits,
                fixture.outbox,
                fixture.clock);
        expiry = new ExpireReservationsUseCase(
                fixture.reservations, fixture.inventories, fixture.purchaseLimits, fixture.outbox, fixture.clock);
    }

    private Reservation reserved(int quantity) {
        var result = reserve.execute(new ReserveStockCommand(UUID.randomUUID().toString(), ALICE, product, quantity));
        return ((ReserveStockResult.Reserved) result).reservation();
    }

    private ReservationStatus statusOf(Reservation reservation) {
        return fixture.reservations.findById(reservation.id()).orElseThrow().status();
    }

    private void assertStock(int available, int reserved, int sold) {
        Inventory inventory = fixture.inventoryOf(product);
        assertThat(inventory.availableQuantity()).as("available").isEqualTo(available);
        assertThat(inventory.reservedQuantity()).as("reserved").isEqualTo(reserved);
        assertThat(inventory.soldQuantity()).as("sold").isEqualTo(sold);
    }

    @Nested
    class Cancellation {

        @Test
        void returnsStockAndAllowanceAndPublishesReservationReleased() {
            Reservation reservation = reserved(2);

            CancelReservationResult result = cancel.execute(reservation.id(), Optional.of(ALICE));

            assertThat(result).isInstanceOf(CancelReservationResult.Cancelled.class);
            assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CANCELLED);
            assertStock(10, 0, 0);
            assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isZero();
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationReleased.class))
                    .singleElement()
                    .satisfies(event ->
                            assertThat(event.reason()).isEqualTo(CancelReservationUseCase.REASON_USER_CANCELLED));
        }

        @Test
        void anotherUsersReservationLooksLikeItDoesNotExist() {
            Reservation reservation = reserved(1);

            assertThat(cancel.execute(reservation.id(), Optional.of(MALLORY)))
                    .isEqualTo(new CancelReservationResult.NotFound(reservation.id()));
            assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        void onlyPendingReservationsCanBeCancelled() {
            Reservation reservation = reserved(1);
            cancel.execute(reservation.id(), Optional.empty());

            assertThat(cancel.execute(reservation.id(), Optional.empty()))
                    .isEqualTo(new CancelReservationResult.InvalidState(reservation.id(), ReservationStatus.CANCELLED));
            assertStock(10, 0, 0);
        }
    }

    @Nested
    class SagaConfirmation {

        @Test
        void sellsTheReservedUnitsAndKeepsThemCountedAgainstTheLimit() {
            Reservation reservation = reserved(2);

            saga.confirm(
                    UUID.randomUUID(),
                    new InventoryMessages.ConfirmReservation(reservation.id().value(), ORDER));

            assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
            assertStock(8, 0, 2);
            assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isEqualTo(2);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationConfirmed.class))
                    .containsExactly(new InventoryMessages.ReservationConfirmed(
                            reservation.id().value(), ORDER, product.value(), 2));
        }

        @Test
        void aDuplicateCommandIsIgnored() {
            Reservation reservation = reserved(1);
            UUID eventId = UUID.randomUUID();
            var command =
                    new InventoryMessages.ConfirmReservation(reservation.id().value(), ORDER);

            assertThat(saga.confirm(eventId, command)).isTrue();
            assertThat(saga.confirm(eventId, command)).isFalse();

            assertStock(9, 0, 1);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationConfirmed.class))
                    .hasSize(1);
        }

        @Test
        void confirmingAnExpiredReservationIsRejectedSoTheSagaRefunds() {
            Reservation reservation = reserved(1);
            fixture.clock.advance(Duration.ofMinutes(11));
            expiry.expireBatch(100);

            saga.confirm(
                    UUID.randomUUID(),
                    new InventoryMessages.ConfirmReservation(reservation.id().value(), ORDER));

            assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.EXPIRED);
            assertStock(10, 0, 0);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationConfirmationRejected.class))
                    .containsExactly(new InventoryMessages.ReservationConfirmationRejected(
                            reservation.id().value(), ORDER, "EXPIRED"));
        }

        @Test
        void anUnknownReservationIsRejectedRatherThanFailingTheCommand() {
            UUID unknown = UUID.randomUUID();

            saga.confirm(UUID.randomUUID(), new InventoryMessages.ConfirmReservation(unknown, ORDER));

            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationConfirmationRejected.class))
                    .containsExactly(
                            new InventoryMessages.ReservationConfirmationRejected(unknown, ORDER, "NOT_FOUND"));
        }
    }

    @Nested
    class SagaCompensation {

        @Test
        void releasesThePendingReservation() {
            Reservation reservation = reserved(2);

            saga.release(
                    UUID.randomUUID(),
                    new InventoryMessages.ReleaseReservation(reservation.id().value(), ORDER, "PAYMENT_FAILED"));

            assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CANCELLED);
            assertStock(10, 0, 0);
            assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isZero();
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationReleased.class))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.orderId()).isEqualTo(ORDER);
                        assertThat(event.reason()).isEqualTo("PAYMENT_FAILED");
                    });
        }

        @Test
        void releasingAnAlreadyExpiredReservationChangesNothing() {
            Reservation reservation = reserved(1);
            fixture.clock.advance(Duration.ofMinutes(11));
            expiry.expireBatch(100);
            int messagesBefore = fixture.outbox.messages().size();

            saga.release(
                    UUID.randomUUID(),
                    new InventoryMessages.ReleaseReservation(reservation.id().value(), ORDER, "SAGA_TIMEOUT"));

            assertStock(10, 0, 0);
            assertThat(fixture.outbox.messages()).hasSize(messagesBefore);
        }
    }

    @Nested
    class Expiry {

        @Test
        void releasesOnlyReservationsPastTheirExpiry() {
            Reservation early = reserved(1);
            fixture.clock.advance(Duration.ofMinutes(5));
            Reservation late = reserved(1);
            fixture.clock.advance(Duration.ofMinutes(6));

            assertThat(expiry.expireBatch(100)).isEqualTo(1);

            assertThat(statusOf(early)).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(statusOf(late)).isEqualTo(ReservationStatus.PENDING);
            assertStock(9, 1, 0);
            assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isEqualTo(1);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReservationExpired.class))
                    .containsExactly(new InventoryMessages.ReservationExpired(
                            early.id().value(), ALICE.value(), product.value(), 1));
        }

        @Test
        void aggregatesTheStockOfABatchPerProduct() {
            UserId bob = UserId.of(UUID.randomUUID());
            reserved(2);
            reserve.execute(new ReserveStockCommand("bob", bob, product, 2));
            fixture.clock.advance(Duration.ofMinutes(11));

            assertThat(expiry.expireBatch(100)).isEqualTo(2);

            assertStock(10, 0, 0);
            assertThat(fixture.purchaseLimits.heldBy(bob, product)).isZero();
        }

        @Test
        void aConfirmedReservationNeverExpires() {
            Reservation reservation = reserved(1);
            saga.confirm(
                    UUID.randomUUID(),
                    new InventoryMessages.ConfirmReservation(reservation.id().value(), ORDER));
            fixture.clock.advance(Duration.ofHours(1));

            assertThat(expiry.expireBatch(100)).isZero();
            assertStock(9, 0, 1);
        }
    }
}
