package com.stockforge.payment.config;

import com.stockforge.payment.domain.port.PaymentProvider.ChargeOutcome;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed configuration of the payment service ({@code payment.*}). */
@ConfigurationProperties("payment")
public record PaymentProperties(@DefaultValue Simulation simulation) {

    /**
     * The simulated payment provider (spec §5: no real money moves).
     *
     * @param allowRequestedOutcome honour the outcome a test asked for (FR-6); enable only outside production
     * @param defaultOutcome outcome when none was requested or requests are not honoured
     * @param latency simulated provider response time
     * @param timeout payment timeout: a provider slower than this counts as TIMED_OUT (spec §23 A)
     */
    public record Simulation(
            @DefaultValue("false") boolean allowRequestedOutcome,
            @DefaultValue("SUCCEEDED") ChargeOutcome defaultOutcome,
            @DefaultValue("20ms") Duration latency,
            @DefaultValue("2s") Duration timeout) {}
}
