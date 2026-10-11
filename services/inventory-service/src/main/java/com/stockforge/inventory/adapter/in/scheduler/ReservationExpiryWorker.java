package com.stockforge.inventory.adapter.in.scheduler;

import com.stockforge.inventory.application.ExpireReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled worker expiring overdue reservations (spec §21, W4-05). */
@Component
public class ReservationExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryWorker.class);

    private final ExpireReservationsUseCase expireReservationsUseCase;

    public ReservationExpiryWorker(ExpireReservationsUseCase expireReservationsUseCase) {
        this.expireReservationsUseCase = expireReservationsUseCase;
    }

    @Scheduled(fixedDelayString = "${stockforge.expiry.poll-interval-ms:1000}")
    public void runExpiryScan() {
        try {
            int expired = expireReservationsUseCase.expirePendingBatch(100);
            if (expired > 0) {
                log.info("Expired {} overdue pending reservations and restored stock", expired);
            }
        } catch (Exception e) {
            log.error("Failed running reservation expiry scan: {}", e.getMessage(), e);
        }
    }
}
