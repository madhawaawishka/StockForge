package com.stockforge.payment.application;

import com.stockforge.events.PaymentMessages;
import com.stockforge.payment.domain.model.Money;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.domain.model.UserId;
import com.stockforge.payment.domain.port.IdGenerator;
import com.stockforge.payment.domain.port.PaymentProvider;
import com.stockforge.payment.domain.port.PaymentProvider.ChargeOutcome;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Charges an order: saga step 3 (spec §16 B, FR-6).
 *
 * <ol>
 *   <li>Transaction 1 registers a PENDING payment, at most one per order.
 *   <li>The provider is called with no transaction open (spec §15).
 *   <li>Transaction 2 records the outcome and its event.
 * </ol>
 *
 * <p>If the process crashes between the steps, the command is redelivered, finds the PENDING payment and charges again;
 * the provider is idempotent per payment id, so the customer is charged once. A redelivery after step 3 finds a decided
 * payment and does nothing.
 */
public class ProcessPaymentUseCase {

    private final PaymentTransactions transactions;
    private final PaymentProvider provider;
    private final IdGenerator ids;
    private final Clock clock;

    public ProcessPaymentUseCase(
            PaymentTransactions transactions, PaymentProvider provider, IdGenerator ids, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns the payment's status after this attempt. */
    public PaymentStatus execute(UUID eventId, PaymentMessages.RequestPayment command) {
        Payment payment = transactions.register(Payment.request(
                ids.newPaymentId(),
                OrderId.of(command.orderId()),
                UserId.of(command.userId()),
                Money.of(command.amount(), command.currency()),
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        if (payment.status() != PaymentStatus.PENDING) {
            return payment.status(); // already decided by an earlier delivery
        }
        ChargeOutcome outcome = provider.charge(payment, Optional.ofNullable(command.simulatedOutcome()));
        if (outcome == ChargeOutcome.NO_RESPONSE) {
            return PaymentStatus.PENDING; // nothing to announce; the order saga times out and cancels
        }
        transactions.recordOutcome(payment, outcome, eventId);
        return switch (outcome) {
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case DECLINED -> PaymentStatus.FAILED;
            case TIMED_OUT -> PaymentStatus.TIMED_OUT;
            case NO_RESPONSE -> PaymentStatus.PENDING;
        };
    }
}
