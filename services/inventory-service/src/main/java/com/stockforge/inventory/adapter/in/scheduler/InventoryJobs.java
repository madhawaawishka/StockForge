package com.stockforge.inventory.adapter.in.scheduler;

import com.stockforge.inventory.application.ExpireReservationsUseCase;
import com.stockforge.inventory.application.PurgeExpiredIdempotencyKeysUseCase;
import com.stockforge.inventory.application.ReconcileInventoryUseCase;
import com.stockforge.inventory.config.InventoryProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Background work of the inventory service (spec §10 "Inventory Worker", §21). Every job is safe on any number of
 * instances: expiry claims rows with SKIP LOCKED, reconciliation only reads, cleanup deletes idempotently.
 *
 * <p>Metrics: {@code stockforge_inventory_invariant_violations} (must always be 0, the most important business alarm,
 * spec §25) and {@code stockforge_reservations_expired_total}.
 */
@Component
class InventoryJobs implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(InventoryJobs.class);

    private final ExpireReservationsUseCase expireReservations;
    private final ReconcileInventoryUseCase reconcile;
    private final PurgeExpiredIdempotencyKeysUseCase purgeIdempotencyKeys;
    private final InventoryProperties properties;
    private final AtomicInteger invariantViolations = new AtomicInteger();
    private final Counter expired;

    InventoryJobs(
            ExpireReservationsUseCase expireReservations,
            ReconcileInventoryUseCase reconcile,
            PurgeExpiredIdempotencyKeysUseCase purgeIdempotencyKeys,
            InventoryProperties properties,
            MeterRegistry meters) {
        this.expireReservations = expireReservations;
        this.reconcile = reconcile;
        this.purgeIdempotencyKeys = purgeIdempotencyKeys;
        this.properties = properties;
        Gauge.builder("stockforge.inventory.invariant.violations", invariantViolations, AtomicInteger::get)
                .description("Inventory invariant violations found by the last reconciliation; must be 0")
                .register(meters);
        this.expired = Counter.builder("stockforge.reservations.expired")
                .description("Reservations released by the expiry worker")
                .register(meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(
                this::expireReservations, properties.expiry().pollInterval());
        registrar.addFixedDelayTask(this::reconcile, properties.reconciliation().interval());
        registrar.addFixedDelayTask(
                this::purgeIdempotencyKeys, properties.idempotency().cleanupInterval());
    }

    /** Drains the backlog in batches, so a burst of expiries is released within one poll interval (NFR-8). */
    void expireReservations() {
        try {
            int batch;
            do {
                batch = expireReservations.expireBatch(properties.expiry().batchSize());
                expired.increment(batch);
            } while (batch == properties.expiry().batchSize());
        } catch (RuntimeException failure) {
            log.warn("Reservation expiry run failed; retrying next interval: {}", failure.toString());
        }
    }

    void reconcile() {
        try {
            ReconcileInventoryUseCase.Report report = reconcile.reconcile();
            invariantViolations.set(report.violations());
            if (report.violations() > 0) {
                // Report, never repair: the rows are the evidence of a bug (RUNBOOK-001).
                log.error(
                        "INVENTORY INVARIANT VIOLATED: conservation={} reserved={} purchaseLimit={}",
                        report.conservationViolations(),
                        report.reservedMismatches(),
                        report.purchaseLimitMismatches());
            }
        } catch (RuntimeException failure) {
            log.warn("Inventory reconciliation failed; retrying next interval: {}", failure.toString());
        }
    }

    void purgeIdempotencyKeys() {
        try {
            int deleted;
            int total = 0;
            do {
                deleted = purgeIdempotencyKeys.purgeBatch();
                total += deleted;
            } while (deleted > 0);
            if (total > 0) {
                log.info("Deleted {} expired idempotency keys", total);
            }
        } catch (RuntimeException failure) {
            log.warn("Idempotency key cleanup failed; retrying next interval: {}", failure.toString());
        }
    }
}
