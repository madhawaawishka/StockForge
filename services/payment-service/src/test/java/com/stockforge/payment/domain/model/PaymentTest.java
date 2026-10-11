package com.stockforge.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private final PaymentId paymentId = PaymentId.of(UUID.randomUUID());
    private final OrderId orderId = OrderId.of(UUID.randomUUID());
    private final UserId userId = UserId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void paymentCanTransitionFromPendingToSucceeded() {
        Payment payment = Payment.create(paymentId, orderId, userId, new BigDecimal("49.99"), "USD", "SUCCEEDED", now);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);

        Payment succeeded = payment.succeed(now.plusSeconds(1));
        assertThat(succeeded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.simulationOutcome()).isNotNull();
    }

    @Test
    void paymentCanTransitionFromPendingToFailed() {
        Payment payment = Payment.create(paymentId, orderId, userId, new BigDecimal("49.99"), "USD", "FAILED", now);

        Payment failed = payment.fail(now.plusSeconds(1));
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void paymentCanTransitionFromPendingToTimedOut() {
        Payment payment = Payment.create(paymentId, orderId, userId, new BigDecimal("49.99"), "USD", "TIMED_OUT", now);

        Payment timedOut = payment.timeOut(now.plusSeconds(1));
        assertThat(timedOut.status()).isEqualTo(PaymentStatus.TIMED_OUT);
    }

    @Test
    void succeededPaymentCanBeRefunded() {
        Payment payment = Payment.create(paymentId, orderId, userId, new BigDecimal("49.99"), "USD", "SUCCEEDED", now);
        Payment succeeded = payment.succeed(now.plusSeconds(1));

        Payment refunded = succeeded.refund(now.plusSeconds(2));
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void failedPaymentCannotBeRefunded() {
        Payment payment = Payment.create(paymentId, orderId, userId, new BigDecimal("49.99"), "USD", "FAILED", now);
        Payment failed = payment.fail(now.plusSeconds(1));

        assertThatThrownBy(() -> failed.refund(now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only SUCCEEDED payments can be refunded");
    }
}
