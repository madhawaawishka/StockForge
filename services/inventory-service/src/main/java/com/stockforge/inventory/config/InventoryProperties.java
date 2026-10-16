package com.stockforge.inventory.config;

import com.stockforge.inventory.domain.port.StrategyType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration of the inventory service ({@code inventory.*}). Invalid values stop the service at startup
 * instead of surfacing under load (spec §33: fail fast).
 */
@Validated
@ConfigurationProperties("inventory")
public record InventoryProperties(
        @DefaultValue Reservation reservation,
        @DefaultValue Expiry expiry,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Cache cache) {

    /**
     * Reservation flow.
     *
     * @param ttl how long a PENDING reservation holds stock; keep it well above the saga payment timeout
     * @param purchaseLimit maximum units per user and product (FR-4)
     * @param strategy concurrency-control strategy (spec §14, ADR-006)
     * @param optimisticMaxAttempts attempts of the optimistic strategy before it reports a conflict
     * @param allowUnsafeStrategy must be {@code true} to run the NAIVE strategy, which oversells by design
     */
    public record Reservation(
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("2") int purchaseLimit,
            @DefaultValue("ATOMIC_CONDITIONAL_UPDATE") StrategyType strategy,
            @DefaultValue("5") int optimisticMaxAttempts,
            @DefaultValue("false") boolean allowUnsafeStrategy) {

        public Reservation {
            if (strategy == StrategyType.NAIVE && !allowUnsafeStrategy) {
                throw new IllegalArgumentException("inventory.reservation.strategy=NAIVE oversells by design; set "
                        + "inventory.reservation.allow-unsafe-strategy=true to run it for a demonstration");
            }
            if (purchaseLimit < 1 || optimisticMaxAttempts < 1) {
                throw new IllegalArgumentException(
                        "inventory.reservation.purchase-limit and optimistic-max-attempts must be positive");
            }
        }
    }

    /**
     * Reservation expiry worker (spec §21). With the defaults a reservation is released at most about one poll interval
     * after it expires, well inside NFR-8 (30 s).
     */
    public record Expiry(
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("200") int batchSize) {}

    /** Invariant reconciliation job (spec §21). */
    public record Reconciliation(@DefaultValue("60s") Duration interval) {}

    /** Idempotency-key retention (spec §19). */
    public record Idempotency(
            @DefaultValue("24h") Duration keyTtl,
            @DefaultValue("10m") Duration cleanupInterval) {}

    /**
     * Product cache (spec §17).
     *
     * @param productTtl base TTL of a cached product
     * @param productTtlJitter random extra TTL, so entries cached together do not expire together
     * @param availabilityTtl TTL of cached availability; seconds at most, since it is shown as approximate
     */
    public record Cache(
            @DefaultValue("5m") Duration productTtl,
            @DefaultValue("1m") Duration productTtlJitter,
            @DefaultValue("2s") Duration availabilityTtl) {}
}
