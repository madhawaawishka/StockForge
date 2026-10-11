package com.stockforge.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.DomainEvents;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.testsupport.InMemoryOutboxPublisher;
import com.stockforge.payment.testsupport.InMemoryPaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessPaymentUseCaseTest {

    private InMemoryPaymentRepository paymentRepository;
    private InMemoryOutboxPublisher outboxPublisher;
    private Clock clock;
    private ProcessPaymentUseCase useCase;
    private RefundPaymentUseCase refundUseCase;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        outboxPublisher = new InMemoryOutboxPublisher();
        clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
        useCase = new ProcessPaymentUseCase(paymentRepository, outboxPublisher, clock);
        refundUseCase = new RefundPaymentUseCase(paymentRepository, outboxPublisher, clock);
    }

    @Test
    void executeSuccessfulPayment() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProcessPaymentCommand command =
                new ProcessPaymentCommand(orderId, userId, new BigDecimal("100.00"), "USD", "idem-1", "SUCCEEDED");

        ProcessPaymentResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ProcessPaymentResult.Success.class);
        ProcessPaymentResult.Success success = (ProcessPaymentResult.Success) result;
        assertThat(success.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findByOrderId(OrderId.of(orderId))).isPresent();

        assertThat(outboxPublisher.getEvents()).hasSize(1);
        InMemoryOutboxPublisher.PublishedEvent event =
                outboxPublisher.getEvents().get(0);
        assertThat(event.eventType()).isEqualTo("PaymentSucceeded");
        assertThat(event.payload()).isInstanceOf(DomainEvents.PaymentSucceeded.class);
    }

    @Test
    void executeFailedPayment() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProcessPaymentCommand command =
                new ProcessPaymentCommand(orderId, userId, new BigDecimal("100.00"), "USD", "idem-2", "FAILED");

        ProcessPaymentResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ProcessPaymentResult.Failure.class);
        ProcessPaymentResult.Failure failure = (ProcessPaymentResult.Failure) result;
        assertThat(failure.payment().status()).isEqualTo(PaymentStatus.FAILED);

        assertThat(outboxPublisher.getEvents()).hasSize(1);
        InMemoryOutboxPublisher.PublishedEvent event =
                outboxPublisher.getEvents().get(0);
        assertThat(event.eventType()).isEqualTo("PaymentFailed");
        assertThat(event.payload()).isInstanceOf(DomainEvents.PaymentFailed.class);
    }

    @Test
    void executeTimedOutPayment() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProcessPaymentCommand command =
                new ProcessPaymentCommand(orderId, userId, new BigDecimal("100.00"), "USD", "idem-3", "TIMED_OUT");

        ProcessPaymentResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(ProcessPaymentResult.TimedOut.class);
        ProcessPaymentResult.TimedOut timedOut = (ProcessPaymentResult.TimedOut) result;
        assertThat(timedOut.payment().status()).isEqualTo(PaymentStatus.TIMED_OUT);

        assertThat(outboxPublisher.getEvents()).hasSize(1);
        InMemoryOutboxPublisher.PublishedEvent event =
                outboxPublisher.getEvents().get(0);
        assertThat(event.eventType()).isEqualTo("PaymentTimedOut");
    }

    @Test
    void executeRefundPayment() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProcessPaymentCommand command =
                new ProcessPaymentCommand(orderId, userId, new BigDecimal("100.00"), "USD", "idem-4", "SUCCEEDED");
        useCase.execute(command);

        boolean refunded = refundUseCase.execute(OrderId.of(orderId), "Reservation expired");

        assertThat(refunded).isTrue();
        assertThat(paymentRepository.findByOrderId(OrderId.of(orderId)).get().status())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxPublisher.getEvents()).hasSize(2);
        assertThat(outboxPublisher.getEvents().get(1).eventType()).isEqualTo("PaymentRefunded");
    }

    @Test
    void idempotentPaymentExecution() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProcessPaymentCommand command =
                new ProcessPaymentCommand(orderId, userId, new BigDecimal("100.00"), "USD", "idem-5", "SUCCEEDED");

        ProcessPaymentResult first = useCase.execute(command);
        ProcessPaymentResult second = useCase.execute(command);

        assertThat(first).isInstanceOf(ProcessPaymentResult.Success.class);
        assertThat(second).isInstanceOf(ProcessPaymentResult.AlreadyProcessed.class);
        assertThat(outboxPublisher.getEvents()).hasSize(1);
    }
}
