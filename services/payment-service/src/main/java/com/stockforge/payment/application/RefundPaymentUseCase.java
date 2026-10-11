package com.stockforge.payment.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Compensating action refunding payment when subsequent saga steps fail (spec §16). */
public class RefundPaymentUseCase {

    private final PaymentRepository payments;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public RefundPaymentUseCase(PaymentRepository payments, OutboxPublisher outbox, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public boolean execute(OrderId orderId, String reason) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Payment> existing = payments.findByOrderId(orderId);
        if (existing.isEmpty()) {
            return false;
        }

        Payment payment = existing.get();
        Payment refunded = payment.refund(now);
        payments.update(refunded);

        DomainEvents.PaymentRefunded event = new DomainEvents.PaymentRefunded(
                refunded.id().value(), orderId.value(), refunded.amount().toPlainString(), refunded.currency());

        outbox.publish(
                "Payment",
                refunded.id().value().toString(),
                "PaymentRefunded",
                event,
                orderId.value().toString());

        return true;
    }
}
