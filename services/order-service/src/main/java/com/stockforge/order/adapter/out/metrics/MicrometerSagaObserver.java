package com.stockforge.order.adapter.out.metrics;

import com.stockforge.order.domain.port.SagaObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Saga outcomes as Prometheus counters (spec §25): {@code stockforge_checkout_completed_total},
 * {@code stockforge_saga_compensations_total{reason}} and {@code stockforge_saga_refunds_total{reason}}.
 *
 * <p>Every counter is created at start-up, at zero, for every reason. A counter that appeared only with its first
 * increment would be invisible to {@code rate()} until its second one — the first compensation would never reach the
 * {@code SagaCompensationRateHigh} alert — and dashboards would show "No data" instead of 0.
 */
@Component
class MicrometerSagaObserver implements SagaObserver {

    private final Counter completed;
    private final Map<Compensation, Counter> compensations = new EnumMap<>(Compensation.class);
    private final Map<Refund, Counter> refunds = new EnumMap<>(Refund.class);

    MicrometerSagaObserver(MeterRegistry meters) {
        this.completed = Counter.builder("stockforge.checkout.completed")
                .description("Orders confirmed by the checkout saga")
                .register(meters);
        for (Compensation reason : Compensation.values()) {
            compensations.put(
                    reason,
                    Counter.builder("stockforge.saga.compensations")
                            .description("Checkouts the saga compensated (order cancelled, stock released)")
                            .tag("reason", reason.name())
                            .register(meters));
        }
        for (Refund reason : Refund.values()) {
            refunds.put(
                    reason,
                    Counter.builder("stockforge.saga.refunds")
                            .description("Payments the saga had to refund")
                            .tag("reason", reason.name())
                            .register(meters));
        }
    }

    @Override
    public void checkoutCompleted() {
        completed.increment();
    }

    @Override
    public void compensated(Compensation reason) {
        compensations.get(reason).increment();
    }

    @Override
    public void refundRequested(Refund reason) {
        refunds.get(reason).increment();
    }
}
