package com.stockforge.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed configuration of the order service ({@code order.*}); invalid values stop startup (spec §33). */
@ConfigurationProperties("order")
public record OrderProperties(
        @DefaultValue Checkout checkout,
        @DefaultValue Inventory inventory,
        @DefaultValue Idempotency idempotency) {

    /**
     * Checkout saga timing (spec §16).
     *
     * @param paymentTimeout orders waiting longer than this for their payment result are cancelled; well below the
     *     reservation TTL (10 minutes by default)
     * @param timeoutScanInterval how often the saga-timeout job looks for such orders
     */
    public record Checkout(
            @DefaultValue("2m") Duration paymentTimeout,
            @DefaultValue("5s") Duration timeoutScanInterval) {}

    /**
     * Calls to the Inventory service (spec §23 A).
     *
     * @param deadline gRPC deadline of each call; the order request fails fast instead of waiting on a slow dependency
     */
    public record Inventory(@DefaultValue("1s") Duration deadline) {}

    /** Idempotency-key retention (spec §19). */
    public record Idempotency(
            @DefaultValue("24h") Duration keyTtl,
            @DefaultValue("10m") Duration cleanupInterval) {}
}
