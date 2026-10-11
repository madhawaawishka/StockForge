package com.stockforge.payment.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.model.UserId;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Executes payment processing with controllable simulation outcomes (spec §16, FR-6). */
public class ProcessPaymentUseCase {

    private final PaymentRepository payments;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ProcessPaymentUseCase(PaymentRepository payments, OutboxPublisher outbox, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ProcessPaymentResult execute(ProcessPaymentCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        OrderId orderId = OrderId.of(command.orderId());

        Optional<Payment> existing = payments.findByOrderId(orderId);
        if (existing.isPresent()) {
            return new ProcessPaymentResult.AlreadyProcessed(existing.get());
        }

        PaymentId paymentId = PaymentId.of(UUID.randomUUID());
        Payment payment = Payment.create(
                paymentId,
                orderId,
                UserId.of(command.userId()),
                command.amount(),
                command.currency(),
                command.simulationOutcome(),
                now);

        String outcome = command.simulationOutcome() == null
                ? "SUCCEEDED"
                : command.simulationOutcome().toUpperCase(java.util.Locale.ROOT);

        if ("FAILED".equals(outcome)) {
            Payment failedPayment = payment.fail(now);
            payments.insert(failedPayment);

            DomainEvents.PaymentFailed event =
                    new DomainEvents.PaymentFailed(paymentId.value(), orderId.value(), "Simulated payment declined");
            outbox.publish(
                    "Payment",
                    paymentId.value().toString(),
                    "PaymentFailed",
                    event,
                    orderId.value().toString());
            return new ProcessPaymentResult.Failure(failedPayment, "Simulated payment declined");
        } else if ("TIMED_OUT".equals(outcome)) {
            Payment timedOutPayment = payment.timeOut(now);
            payments.insert(timedOutPayment);

            DomainEvents.PaymentTimedOut event = new DomainEvents.PaymentTimedOut(paymentId.value(), orderId.value());
            outbox.publish(
                    "Payment",
                    paymentId.value().toString(),
                    "PaymentTimedOut",
                    event,
                    orderId.value().toString());
            return new ProcessPaymentResult.TimedOut(timedOutPayment);
        } else {
            Payment succeededPayment = payment.succeed(now);
            payments.insert(succeededPayment);

            DomainEvents.PaymentSucceeded event = new DomainEvents.PaymentSucceeded(
                    paymentId.value(), orderId.value(), command.amount().toPlainString(), command.currency());
            outbox.publish(
                    "Payment",
                    paymentId.value().toString(),
                    "PaymentSucceeded",
                    event,
                    orderId.value().toString());
            return new ProcessPaymentResult.Success(succeededPayment);
        }
    }
}
