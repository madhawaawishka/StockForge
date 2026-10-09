package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

/** Persistence port for idempotency keys (spec §19). */
public interface IdempotencyKeyRepository {

    /**
     * Attempts to acquire an in-progress lock for the idempotency key. Returns true if successfully inserted
     * (acquired), false if key already exists.
     */
    boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt);

    Optional<IdempotencyRecord> findByKeyAndUser(String key, UserId userId);

    void complete(String key, UserId userId, int responseStatus, String responsePayload);

    void fail(String key, UserId userId);
}
