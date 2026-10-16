package com.stockforge.payment.adapter.out.provider;

import com.stockforge.payment.config.PaymentProperties;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.port.PaymentProvider;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for an external payment provider (spec §5, FR-6). It waits for the configured latency like a remote call
 * would, gives up at the payment timeout, and lets non-production environments choose the outcome per request so that
 * every saga path can be tested deterministically.
 */
@Component
class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final PaymentProperties.Simulation config;

    SimulatedPaymentProvider(PaymentProperties properties) {
        this.config = properties.simulation();
    }

    @Override
    public ChargeOutcome charge(Payment payment, Optional<String> requestedOutcome) {
        ChargeOutcome outcome = requestedOutcome
                .filter(requested -> config.allowRequestedOutcome())
                .map(SimulatedPaymentProvider::parse)
                .orElse(config.defaultOutcome());
        if (config.latency().compareTo(config.timeout()) >= 0) {
            waitFor(config.timeout());
            return ChargeOutcome.TIMED_OUT;
        }
        waitFor(config.latency());
        log.debug("Simulated charge of {} for order {}: {}", payment.amount(), payment.orderId(), outcome);
        return outcome;
    }

    @Override
    public void refund(Payment payment) {
        waitFor(config.latency());
        log.debug("Simulated refund of {} for order {}", payment.amount(), payment.orderId());
    }

    private static ChargeOutcome parse(String requested) {
        return switch (requested.toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED" -> ChargeOutcome.SUCCEEDED;
            case "FAILED" -> ChargeOutcome.DECLINED;
            case "TIMED_OUT" -> ChargeOutcome.TIMED_OUT;
            case "NO_RESPONSE" -> ChargeOutcome.NO_RESPONSE;
            default -> throw new IllegalArgumentException("Unknown simulated payment outcome " + requested);
        };
    }

    /** The simulated network round trip; this thread is a Kafka listener, never a database transaction. */
    private static void waitFor(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the payment provider", interrupted);
        }
    }
}
