package com.stockforge.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final OrderId ORDER_ID = OrderId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-4a0b-7c09-8d21-0a1b2c3d4e5f"));

    @Test
    void createsOrderInPendingPaymentStatusWithTotalCalculation() {
        OrderItem item1 = new OrderItem(PRODUCT_ID, 2, Money.of("25.00", "USD"));
        OrderItem item2 = new OrderItem(PRODUCT_ID, 1, Money.of("50.00", "USD"));

        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item1, item2), NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.totalAmount()).isEqualTo(Money.of("100.00", "USD"));
        assertThat(order.history()).hasSize(1);
        assertThat(order.history().getFirst().toStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void transitionsToConfirmed() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("100.00", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);

        Instant later = NOW.plusSeconds(30);
        Order confirmed = order.confirm(later, "Payment succeeded");

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.history()).hasSize(2);
        assertThat(confirmed.history().get(1).toStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.history().get(1).reason()).isEqualTo("Payment succeeded");
    }

    @Test
    void transitionsToCancelled() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("100.00", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);

        Instant later = NOW.plusSeconds(30);
        Order cancelled = order.cancel(later, "Payment failed");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.history()).hasSize(2);
    }

    @Test
    void transitionsToRefundPendingThenRefunded() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("100.00", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);

        Instant t1 = NOW.plusSeconds(30);
        Order refundPending = order.refundPending(t1, "Reservation expired before confirmation");
        assertThat(refundPending.status()).isEqualTo(OrderStatus.REFUND_PENDING);

        Instant t2 = t1.plusSeconds(10);
        Order refunded = refundPending.refund(t2, "Refund processed");
        assertThat(refunded.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void rejectsTransitionFromTerminalState() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("100.00", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);
        Order confirmed = order.confirm(NOW.plusSeconds(10), "Paid");

        assertThatThrownBy(() -> confirmed.cancel(NOW.plusSeconds(20), "Too late"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("illegal transition");
    }
}
