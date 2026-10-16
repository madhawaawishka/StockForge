package com.stockforge.payment.application;

import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.OutboxPublisher.OutboundMessage;
import com.stockforge.payment.domain.port.PaymentProvider.ChargeOutcome;
import com.stockforge.payment.domain.port.PaymentRepository;
import com.stockforge.payment.domain.port.ProcessedMessages;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short database transactions around the provider calls. Kept in their own bean so that the provider call in
 * {@link ProcessPaymentUseCase} happens between two transactions, never inside one (spec §15).
 */
public class PaymentTransactions {

    public static final String REFUND_CONSUMER = "payment-refund-commands";

    private final PaymentRepository payments;
    private final OutboxPublisher outbox;
    private final ProcessedMessages inbox;
    private final Clock clock;

    public PaymentTransactions(
            PaymentRepository payments, OutboxPublisher outbox, ProcessedMessages inbox, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** First transaction: one PENDING payment per order; a redelivered request finds the existing payment. */
    @Transactional
    public Payment register(Payment requested) {
        return payments.insertOrGetForOrder(requested);
    }

    /** Second transaction: the provider's answer and the event announcing it commit together. */
    @Transactional
    public void recordOutcome(Payment pending, ChargeOutcome outcome, UUID causationId) {
        Instant now = now();
        Payment decided = switch (outcome) {
            case SUCCEEDED -> pending.succeed(now);
            case DECLINED -> pending.fail(now);
            case TIMED_OUT -> pending.timeOut(now);
            case NO_RESPONSE -> throw new IllegalArgumentException("NO_RESPONSE has no outcome to record");
        };
        if (!payments.saveTransition(pending, decided)) {
            return; // a concurrent redelivery recorded the outcome first
        }
        String orderId = decided.orderId().toString();
        Object event = switch (decided.status()) {
            case SUCCEEDED ->
                new PaymentMessages.PaymentSucceeded(
                        decided.id().value(),
                        decided.orderId().value(),
                        decided.amount().amount().toPlainString(),
                        decided.amount().currency().getCurrencyCode());
            case FAILED ->
                new PaymentMessages.PaymentFailed(
                        decided.id().value(), decided.orderId().value(), "Declined by the payment provider");
            case TIMED_OUT ->
                new PaymentMessages.PaymentTimedOut(
                        decided.id().value(), decided.orderId().value());
            case PENDING, REFUNDED -> throw new IllegalStateException("Unexpected outcome " + decided.status());
        };
        outbox.publish(new OutboundMessage(
                Topics.PAYMENT_EVENTS,
                PaymentMessages.AGGREGATE_PAYMENT,
                orderId,
                event.getClass().getSimpleName(),
                event,
                orderId,
                causationId.toString()));
    }

    /**
     * Records a completed refund. The inbox makes a redelivered refund command a no-op.
     *
     * @return {@code false} if the command was a duplicate
     */
    @Transactional
    public boolean recordRefund(UUID eventId, Payment captured) {
        if (!inbox.markProcessed(REFUND_CONSUMER, eventId)) {
            return false;
        }
        Payment refunded = captured.refund(now());
        if (payments.saveTransition(captured, refunded)) {
            String orderId = refunded.orderId().toString();
            outbox.publish(new OutboundMessage(
                    Topics.PAYMENT_EVENTS,
                    PaymentMessages.AGGREGATE_PAYMENT,
                    orderId,
                    PaymentMessages.PaymentRefunded.TYPE,
                    new PaymentMessages.PaymentRefunded(
                            refunded.id().value(),
                            refunded.orderId().value(),
                            refunded.amount().amount().toPlainString(),
                            refunded.amount().currency().getCurrencyCode()),
                    orderId,
                    eventId.toString()));
        }
        return true;
    }

    /** Remembers a refund command that needed no refund, so a redelivery does not look new. */
    @Transactional
    public void acknowledgeRefundCommand(UUID eventId) {
        inbox.markProcessed(REFUND_CONSUMER, eventId);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
