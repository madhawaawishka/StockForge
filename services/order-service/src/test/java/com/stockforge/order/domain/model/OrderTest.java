package com.stockforge.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The order state machine and saga steps (spec §16 A). */
class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final Instant LATER = NOW.plusSeconds(30);
    private static final UUID PAYMENT = UUID.randomUUID();

    private static Order placed() {
        return Order.place(
                OrderId.of(UUID.randomUUID()),
                UserId.of(UUID.randomUUID()),
                ReservationId.of(UUID.randomUUID()),
                List.of(
                        new OrderItem(ProductId.of(UUID.randomUUID()), 2, Money.of("19.99", "USD")),
                        new OrderItem(ProductId.of(UUID.randomUUID()), 1, Money.of("5.00", "USD"))),
                NOW);
    }

    @Test
    void aPlacedOrderWaitsForPaymentWithTheTotalOfItsPriceSnapshots() {
        Order order = placed();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.sagaStep()).isEqualTo(SagaStep.PAYMENT_REQUESTED);
        assertThat(order.totalAmount()).isEqualTo(Money.of("44.98", "USD"));
        assertThat(order.history())
                .singleElement()
                .satisfies(entry -> assertThat(entry.toStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT));
    }

    @Test
    void happyPathRecordsEveryStatusChange() {
        Order confirmed = placed().paymentSucceeded(PAYMENT, NOW).confirm(LATER);

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.sagaStep()).isEqualTo(SagaStep.COMPLETED);
        assertThat(confirmed.paymentId()).contains(PAYMENT);
        assertThat(confirmed.history())
                .extracting(OrderStatusHistoryEntry::toStatus)
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED);
    }

    @Test
    void refundPathAfterTheReservationWasLost() {
        Order refunded = placed().paymentSucceeded(PAYMENT, NOW)
                .refundPending("Reservation EXPIRED", LATER)
                .refunded(LATER);

        assertThat(refunded.history())
                .extracting(OrderStatusHistoryEntry::toStatus)
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);
    }

    @Test
    void anOrderWhosePaymentSucceededCanNoLongerBeCancelledByTheTimeout() {
        Order paid = placed().paymentSucceeded(PAYMENT, NOW);

        assertThatThrownBy(() -> paid.cancel("timeout", LATER)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void terminalStatesAreFinal() {
        Order cancelled = placed().cancel("Payment failed", LATER);

        assertThatThrownBy(() -> cancelled.paymentSucceeded(PAYMENT, LATER))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> cancelled.confirm(LATER)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> cancelled.cancel("again", LATER)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void aTotalThatDoesNotMatchTheItemsIsRejected() {
        Order order = placed();

        assertThatThrownBy(() -> new Order(
                        order.id(),
                        order.userId(),
                        order.reservationId(),
                        order.status(),
                        order.sagaStep(),
                        order.paymentId(),
                        Money.of("1.00", "USD"),
                        order.items(),
                        order.history(),
                        order.createdAt(),
                        order.updatedAt()))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void itemsInDifferentCurrenciesCannotBeTotalled() {
        assertThatThrownBy(() -> Order.place(
                        OrderId.of(UUID.randomUUID()),
                        UserId.of(UUID.randomUUID()),
                        ReservationId.of(UUID.randomUUID()),
                        List.of(
                                new OrderItem(ProductId.of(UUID.randomUUID()), 1, Money.of("1.00", "USD")),
                                new OrderItem(ProductId.of(UUID.randomUUID()), 1, Money.of("1.00", "EUR"))),
                        NOW))
                .isInstanceOf(DomainValidationException.class);
    }
}
