package com.stockforge.order.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.order.domain.port.SagaObserver.Compensation;
import com.stockforge.order.domain.port.SagaObserver.Refund;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerSagaObserverTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerSagaObserver observer = new MicrometerSagaObserver(meters);

    @Test
    void everyReasonIsExportedAtZeroBeforeItFirstHappens() {
        assertThat(meters.find("stockforge.saga.compensations").counters()).hasSize(Compensation.values().length);
        assertThat(meters.find("stockforge.saga.refunds").counters()).hasSize(Refund.values().length);
        assertThat(meters.get("stockforge.saga.compensations")
                        .tag("reason", "PAYMENT_TIMEOUT")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void outcomesAreCountedPerReason() {
        observer.compensated(Compensation.PAYMENT_FAILED);
        observer.refundRequested(Refund.forRejectedReservation("expired"));
        observer.checkoutCompleted();

        assertThat(meters.get("stockforge.saga.compensations")
                        .tag("reason", "PAYMENT_FAILED")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("stockforge.saga.refunds")
                        .tag("reason", "RESERVATION_EXPIRED")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("stockforge.checkout.completed").counter().count())
                .isEqualTo(1);
    }

    @Test
    void anUnexpectedReservationStatusCannotCreateNewLabelValues() {
        assertThat(Refund.forRejectedReservation("SOMETHING_NEW")).isEqualTo(Refund.RESERVATION_OTHER);
        assertThat(Refund.forRejectedReservation(null)).isEqualTo(Refund.RESERVATION_OTHER);
    }
}
