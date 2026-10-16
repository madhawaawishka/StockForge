package com.stockforge.payment.application;

import com.stockforge.events.PaymentMessages;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.domain.port.PaymentProvider;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Compensation for saga step 3 (spec §16 B): refunds a captured payment whose order could not be completed, for example
 * because the reservation expired before the payment arrived (spec §16 C). The provider refund runs outside any
 * transaction; it is idempotent per payment, so a retry after a crash refunds once.
 */
public class RefundPaymentUseCase {

    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final PaymentProvider provider;

    public RefundPaymentUseCase(
            PaymentRepository payments, PaymentTransactions transactions, PaymentProvider provider) {
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    /** Returns {@code true} if a refund was recorded by this call. */
    public boolean execute(UUID eventId, PaymentMessages.RefundPayment command) {
        Optional<Payment> payment = payments.findByOrderId(OrderId.of(command.orderId()));
        if (payment.isEmpty() || payment.get().status() != PaymentStatus.SUCCEEDED) {
            // Nothing was captured (or it is refunded already): there is no money to return.
            transactions.acknowledgeRefundCommand(eventId);
            return false;
        }
        provider.refund(payment.get());
        return transactions.recordRefund(eventId, payment.get());
    }
}
