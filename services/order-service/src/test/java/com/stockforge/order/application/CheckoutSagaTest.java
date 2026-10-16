package com.stockforge.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.OrderMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.SagaStep;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.InventoryCatalog.ReservationSnapshot;
import com.stockforge.order.testsupport.OrderFixture;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Every path of the checkout saga (spec §16 B and C), driven by the events it receives. */
class CheckoutSagaTest {

    private static final UserId ALICE = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));

    private OrderFixture fixture;
    private CheckoutSaga saga;
    private Order order;
    private UUID paymentId;

    @BeforeEach
    void placeAnOrder() {
        fixture = new OrderFixture();
        saga = new CheckoutSaga(
                fixture.inbox, fixture.orders, fixture.outbox, fixture.observer, fixture.clock, OrderFixture.POLICY);
        ReservationId reservation = ReservationId.of(UUID.randomUUID());
        fixture.inventory.reservation(
                new ReservationSnapshot(
                        reservation,
                        ALICE,
                        ProductId.of(UUID.randomUUID()),
                        1,
                        "PENDING",
                        OrderFixture.START.plus(Duration.ofMinutes(10))),
                Money.of("25.00", "USD"));
        new PlaceOrderUseCase(
                        fixture.inventory,
                        new PlaceOrderTransaction(
                                fixture.orders, fixture.idempotencyKeys, fixture.outbox, OrderFixture.POLICY),
                        fixture.ids,
                        fixture.clock)
                .execute(new PlaceOrderCommand("key-1", ALICE, reservation, Optional.empty()));
        order = fixture.orders.only();
        paymentId = UUID.randomUUID();
        fixture.outbox.clear();
    }

    private Order current() {
        return fixture.orders.findById(order.id()).orElseThrow();
    }

    private void paymentSucceeds() {
        saga.onPaymentSucceeded(
                UUID.randomUUID(),
                new PaymentMessages.PaymentSucceeded(paymentId, order.id().value(), "25.00", "USD"));
    }

    @Nested
    class HappyPath {

        @Test
        void paymentSuccessAsksInventoryToConfirmTheReservation() {
            paymentSucceeds();

            assertThat(current().sagaStep()).isEqualTo(SagaStep.CONFIRMING_RESERVATION);
            assertThat(current().paymentId()).contains(paymentId);
            assertThat(fixture.outbox.payloads(InventoryMessages.ConfirmReservation.class))
                    .containsExactly(new InventoryMessages.ConfirmReservation(
                            order.reservationId().value(), order.id().value()));
            assertThat(fixture.outbox.messages().getFirst().topic()).isEqualTo(Topics.INVENTORY_COMMANDS);
            assertThat(fixture.outbox.messages().getFirst().aggregateId())
                    .as("commands about a reservation are keyed by the reservation")
                    .isEqualTo(order.reservationId().toString());
        }

        @Test
        void theConfirmedReservationCompletesTheCheckout() {
            paymentSucceeds();

            saga.onReservationConfirmed(
                    UUID.randomUUID(),
                    new InventoryMessages.ReservationConfirmed(
                            order.reservationId().value(), order.id().value(), UUID.randomUUID(), 1));

            assertThat(current().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(fixture.outbox.payloads(OrderMessages.OrderConfirmed.class))
                    .hasSize(1);
            assertThat(fixture.observer.signals).containsExactly("completed");
        }

        @Test
        void aDuplicatePaymentEventIsIgnored() {
            UUID eventId = UUID.randomUUID();
            var event =
                    new PaymentMessages.PaymentSucceeded(paymentId, order.id().value(), "25.00", "USD");

            saga.onPaymentSucceeded(eventId, event);
            saga.onPaymentSucceeded(eventId, event);

            assertThat(fixture.outbox.payloads(InventoryMessages.ConfirmReservation.class))
                    .hasSize(1);
        }
    }

    @Nested
    class Compensation {

        @Test
        void aFailedPaymentCancelsTheOrderAndReleasesTheStock() {
            saga.onPaymentFailed(UUID.randomUUID(), order.id().value(), "Payment failed: card declined");

            assertThat(current().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReleaseReservation.class))
                    .singleElement()
                    .satisfies(release -> assertThat(release.reservationId())
                            .isEqualTo(order.reservationId().value()));
            assertThat(fixture.outbox.payloads(OrderMessages.OrderCancelled.class))
                    .hasSize(1);
            assertThat(fixture.observer.signals).containsExactly("compensated:PAYMENT_FAILED");
        }

        @Test
        void anOrderWithoutPaymentResultIsCancelledAfterThePaymentTimeout() {
            fixture.clock.advance(Duration.ofMinutes(1));
            assertThat(saga.cancelOrdersAwaitingPaymentTooLong(100)).isZero();

            fixture.clock.advance(Duration.ofMinutes(2));
            assertThat(saga.cancelOrdersAwaitingPaymentTooLong(100)).isOne();

            assertThat(current().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(fixture.outbox.payloads(InventoryMessages.ReleaseReservation.class))
                    .hasSize(1);
            assertThat(fixture.observer.signals).containsExactly("compensated:PAYMENT_TIMEOUT");
        }

        @Test
        void theTimeoutNeverCancelsAnOrderWhosePaymentSucceeded() {
            paymentSucceeds();
            fixture.clock.advance(Duration.ofHours(1));

            assertThat(saga.cancelOrdersAwaitingPaymentTooLong(100)).isZero();
            assertThat(current().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void aPaymentThatArrivesAfterTheTimeoutIsRefunded() {
            fixture.clock.advance(Duration.ofMinutes(3));
            saga.cancelOrdersAwaitingPaymentTooLong(100);
            fixture.outbox.clear();

            paymentSucceeds();

            assertThat(current().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(fixture.outbox.payloads(PaymentMessages.RefundPayment.class))
                    .hasSize(1);
            assertThat(fixture.outbox.payloads(InventoryMessages.ConfirmReservation.class))
                    .as("stock is never confirmed for a cancelled order")
                    .isEmpty();
            assertThat(fixture.observer.signals).containsExactly("compensated:PAYMENT_TIMEOUT", "refund:LATE_PAYMENT");
        }
    }

    @Nested
    class PaymentAfterExpiry {

        @Test
        void aRejectedConfirmationRefundsThePayment() {
            paymentSucceeds();

            saga.onReservationConfirmationRejected(
                    UUID.randomUUID(),
                    new InventoryMessages.ReservationConfirmationRejected(
                            order.reservationId().value(), order.id().value(), "EXPIRED"));

            assertThat(current().status()).isEqualTo(OrderStatus.REFUND_PENDING);
            assertThat(fixture.outbox.payloads(PaymentMessages.RefundPayment.class))
                    .singleElement()
                    .satisfies(refund -> assertThat(refund.reason()).contains("EXPIRED"));
            assertThat(fixture.outbox.payloads(OrderMessages.OrderRefundPending.class))
                    .hasSize(1);
            assertThat(fixture.observer.signals).contains("refund:RESERVATION_EXPIRED");
        }

        @Test
        void theRefundCompletesTheOrder() {
            paymentSucceeds();
            saga.onReservationConfirmationRejected(
                    UUID.randomUUID(),
                    new InventoryMessages.ReservationConfirmationRejected(
                            order.reservationId().value(), order.id().value(), "EXPIRED"));

            saga.onPaymentRefunded(
                    UUID.randomUUID(),
                    new PaymentMessages.PaymentRefunded(paymentId, order.id().value(), "25.00", "USD"));

            assertThat(current().status()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(current().history())
                    .extracting(entry -> entry.toStatus())
                    .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);
            assertThat(fixture.outbox.payloads(OrderMessages.OrderRefunded.class))
                    .hasSize(1);
        }
    }
}
