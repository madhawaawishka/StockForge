package com.stockforge.order.adapter.in.scheduler;

import com.stockforge.order.application.ProcessPaymentOutcomeUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled worker scanning for orders stuck in PENDING_PAYMENT beyond timeout (spec §16 B). */
@Component
class SagaTimeoutWorker {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutWorker.class);
    private static final Duration SAGA_TIMEOUT_WINDOW = Duration.ofMinutes(2);

    private final ProcessPaymentOutcomeUseCase processPaymentOutcomeUseCase;
    private final Clock clock;

    SagaTimeoutWorker(ProcessPaymentOutcomeUseCase processPaymentOutcomeUseCase, Clock clock) {
        this.processPaymentOutcomeUseCase = processPaymentOutcomeUseCase;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stockforge.saga.timeout-scan-ms:5000}")
    public void scanAndTimeoutStuckSagas() {
        Instant cutoff = clock.instant().minus(SAGA_TIMEOUT_WINDOW);
        int timedOutCount = processPaymentOutcomeUseCase.handleSagaTimeout(cutoff, 100);
        if (timedOutCount > 0) {
            log.warn("Saga timeout: cancelled and compensated {} orders stuck beyond {}", timedOutCount, cutoff);
        }
    }
}
