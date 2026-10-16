package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes idempotency keys past their window (spec §19). After that a retry with an old key is treated as a new
 * request; clients retry within seconds or minutes, far inside the window.
 */
public class PurgeExpiredIdempotencyKeysUseCase {

    private static final int BATCH_SIZE = 5_000;

    private final IdempotencyKeyRepository idempotencyKeys;
    private final Clock clock;

    public PurgeExpiredIdempotencyKeysUseCase(IdempotencyKeyRepository idempotencyKeys, Clock clock) {
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Deletes in small transactions so the cleanup never holds many locks at once. */
    @Transactional
    public int purgeBatch() {
        return idempotencyKeys.deleteExpired(clock.instant(), BATCH_SIZE);
    }
}
