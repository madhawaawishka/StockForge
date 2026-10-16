package com.stockforge.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The payment state machine (spec §16 A). */
class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");

    private static Payment pending() {
        return Payment.request(
                PaymentId.of(UUID.randomUUID()),
                OrderId.of(UUID.randomUUID()),
                UserId.of(UUID.randomUUID()),
                Money.of("49.99", "USD"),
                NOW);
    }

    @Test
    void aPendingPaymentCanSucceedFailOrTimeOut() {
        assertThat(pending().succeed(NOW).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(pending().fail(NOW).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(pending().timeOut(NOW).status()).isEqualTo(PaymentStatus.TIMED_OUT);
    }

    @Test
    void onlyACapturedPaymentCanBeRefunded() {
        assertThat(pending().succeed(NOW).refund(NOW).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThatThrownBy(() -> pending().refund(NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pending().fail(NOW).refund(NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void decidedPaymentsAreFinal() {
        Payment failed = pending().fail(NOW);

        assertThatThrownBy(() -> failed.succeed(NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pending().succeed(NOW).succeed(NOW)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void theAmountMustBePositive() {
        assertThatThrownBy(() -> Payment.request(
                        PaymentId.of(UUID.randomUUID()),
                        OrderId.of(UUID.randomUUID()),
                        UserId.of(UUID.randomUUID()),
                        Money.of("0.00", "USD"),
                        NOW))
                .isInstanceOf(DomainValidationException.class);
    }
}
