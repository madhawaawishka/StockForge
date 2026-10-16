package com.stockforge.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.PaymentMessages;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.PaymentProvider;
import com.stockforge.payment.domain.port.PaymentRepository;
import com.stockforge.payment.domain.port.ProcessedMessages;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Charging and refunding with an in-memory repository and a scripted provider. */
class PaymentUseCasesTest {

    private final Payments payments = new Payments();
    private final List<OutboxPublisher.OutboundMessage> published = new ArrayList<>();
    private final Set<String> inbox = new HashSet<>();
    private final ScriptedProvider provider = new ScriptedProvider();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private final AtomicLong ids = new AtomicLong();

    private final PaymentTransactions transactions = new PaymentTransactions(
            payments, published::add, (ProcessedMessages) (consumer, eventId) -> inbox.add(consumer + eventId), clock);
    private final ProcessPaymentUseCase charge = new ProcessPaymentUseCase(
            transactions, provider, () -> PaymentId.of(new UUID(0, ids.incrementAndGet())), clock);
    private final RefundPaymentUseCase refund = new RefundPaymentUseCase(payments, transactions, provider);

    private final UUID order = UUID.randomUUID();
    private final PaymentMessages.RequestPayment request =
            new PaymentMessages.RequestPayment(order, UUID.randomUUID(), "39.98", "USD", null);

    @Test
    void aSuccessfulChargeIsRecordedAndAnnounced() {
        assertThat(charge.execute(UUID.randomUUID(), request)).isEqualTo(PaymentStatus.SUCCEEDED);

        assertThat(published).singleElement().satisfies(message -> {
            assertThat(message.eventType()).isEqualTo(PaymentMessages.PaymentSucceeded.TYPE);
            assertThat(message.aggregateId()).isEqualTo(order.toString());
        });
    }

    @Test
    void aRedeliveredRequestNeverChargesTwice() {
        charge.execute(UUID.randomUUID(), request);
        charge.execute(UUID.randomUUID(), request);

        assertThat(provider.charges).isOne();
        assertThat(published).hasSize(1);
    }

    @Test
    void aCrashBeforeTheOutcomeWasRecordedIsRetriedOnRedelivery() {
        provider.next = PaymentProvider.ChargeOutcome.NO_RESPONSE;
        assertThat(charge.execute(UUID.randomUUID(), request)).isEqualTo(PaymentStatus.PENDING);
        assertThat(published).isEmpty();

        provider.next = PaymentProvider.ChargeOutcome.SUCCEEDED;
        assertThat(charge.execute(UUID.randomUUID(), request)).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(published).hasSize(1);
    }

    @Test
    void declinedAndTimedOutChargesAreAnnouncedAsSuch() {
        provider.next = PaymentProvider.ChargeOutcome.DECLINED;
        charge.execute(UUID.randomUUID(), request);

        provider.next = PaymentProvider.ChargeOutcome.TIMED_OUT;
        charge.execute(
                UUID.randomUUID(),
                new PaymentMessages.RequestPayment(UUID.randomUUID(), UUID.randomUUID(), "1.00", "USD", null));

        assertThat(published)
                .extracting(OutboxPublisher.OutboundMessage::eventType)
                .containsExactly(PaymentMessages.PaymentFailed.TYPE, PaymentMessages.PaymentTimedOut.TYPE);
    }

    @Test
    void aCapturedPaymentIsRefundedOnceEvenIfTheCommandIsRedelivered() {
        charge.execute(UUID.randomUUID(), request);
        UUID refundEvent = UUID.randomUUID();
        PaymentMessages.RefundPayment command = new PaymentMessages.RefundPayment(order, "Reservation EXPIRED");

        assertThat(refund.execute(refundEvent, command)).isTrue();
        assertThat(refund.execute(refundEvent, command)).isFalse();

        assertThat(payments.findByOrderId(OrderId.of(order)).orElseThrow().status())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(published)
                .extracting(OutboxPublisher.OutboundMessage::eventType)
                .containsExactly(PaymentMessages.PaymentSucceeded.TYPE, PaymentMessages.PaymentRefunded.TYPE);
    }

    @Test
    void nothingIsRefundedForAFailedPayment() {
        provider.next = PaymentProvider.ChargeOutcome.DECLINED;
        charge.execute(UUID.randomUUID(), request);

        assertThat(refund.execute(UUID.randomUUID(), new PaymentMessages.RefundPayment(order, "test")))
                .isFalse();
        assertThat(provider.refunds).isZero();
    }

    private static final class ScriptedProvider implements PaymentProvider {
        ChargeOutcome next = ChargeOutcome.SUCCEEDED;
        int charges;
        int refunds;

        @Override
        public ChargeOutcome charge(Payment payment, Optional<String> requestedOutcome) {
            charges++;
            return next;
        }

        @Override
        public void refund(Payment payment) {
            refunds++;
        }
    }

    private static final class Payments implements PaymentRepository {
        private final Map<OrderId, Payment> rows = new HashMap<>();

        @Override
        public Payment insertOrGetForOrder(Payment payment) {
            return rows.computeIfAbsent(payment.orderId(), id -> payment);
        }

        @Override
        public Optional<Payment> findByOrderId(OrderId orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public boolean saveTransition(Payment before, Payment after) {
            if (rows.get(before.orderId()).status() != before.status()) {
                return false;
            }
            rows.put(after.orderId(), after);
            return true;
        }
    }
}
