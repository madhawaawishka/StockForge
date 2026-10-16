package com.stockforge.order.adapter.in.scheduler;

import com.stockforge.order.application.CheckoutSaga;
import com.stockforge.order.application.PurgeExpiredIdempotencyKeysUseCase;
import com.stockforge.order.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/** Background work of the order service. Safe on several instances: claims use SKIP LOCKED. */
@Component
class OrderJobs implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);
    private static final int TIMEOUT_BATCH = 100;

    private final CheckoutSaga saga;
    private final PurgeExpiredIdempotencyKeysUseCase purgeIdempotencyKeys;
    private final OrderProperties properties;

    OrderJobs(CheckoutSaga saga, PurgeExpiredIdempotencyKeysUseCase purgeIdempotencyKeys, OrderProperties properties) {
        this.saga = saga;
        this.purgeIdempotencyKeys = purgeIdempotencyKeys;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(
                this::cancelTimedOutCheckouts, properties.checkout().timeoutScanInterval());
        registrar.addFixedDelayTask(
                this::purgeIdempotencyKeys, properties.idempotency().cleanupInterval());
    }

    void cancelTimedOutCheckouts() {
        try {
            int cancelled;
            do {
                cancelled = saga.cancelOrdersAwaitingPaymentTooLong(TIMEOUT_BATCH);
                if (cancelled > 0) {
                    log.warn("Saga timeout: cancelled {} order(s) whose payment did not complete in time", cancelled);
                }
            } while (cancelled == TIMEOUT_BATCH);
        } catch (RuntimeException failure) {
            log.warn("Saga timeout scan failed; retrying next interval: {}", failure.toString());
        }
    }

    void purgeIdempotencyKeys() {
        try {
            int deleted;
            do {
                deleted = purgeIdempotencyKeys.purgeBatch();
            } while (deleted > 0);
        } catch (RuntimeException failure) {
            log.warn("Idempotency key cleanup failed; retrying next interval: {}", failure.toString());
        }
    }
}
