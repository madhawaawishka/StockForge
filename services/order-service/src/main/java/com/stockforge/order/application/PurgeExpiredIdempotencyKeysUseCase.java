package com.stockforge.order.application;

import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Deletes order idempotency keys past their window (spec §19), in small transactions. */
public class PurgeExpiredIdempotencyKeysUseCase {

    private static final int BATCH_SIZE = 5_000;

    private final IdempotencyKeyRepository idempotencyKeys;
    private final Clock clock;

    public PurgeExpiredIdempotencyKeysUseCase(IdempotencyKeyRepository idempotencyKeys, Clock clock) {
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public int purgeBatch() {
        return idempotencyKeys.deleteExpired(clock.instant(), BATCH_SIZE);
    }
}
