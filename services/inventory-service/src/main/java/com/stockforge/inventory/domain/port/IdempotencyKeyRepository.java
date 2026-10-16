package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotency keys, stored in the same transaction as the business change they protect (spec §19).
 *
 * <p>Keys are scoped by caller: the same key from two users is two different keys.
 */
public interface IdempotencyKeyRepository {

    /**
     * Claims the key for this request. A concurrent request with the same key waits for the first transaction to end
     * and then gets {@code false}.
     *
     * @return {@code true} if the key was new and is now claimed
     */
    boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt);

    Optional<IdempotencyRecord> findByKeyAndUser(String key, UserId userId);

    /** Stores the outcome so a retry with the same key replays it. */
    void complete(String key, UserId userId, int responseStatus, String responsePayload);

    /** Forgets the key after a transient failure, so the client may retry the same request. */
    void release(String key, UserId userId);

    /** Deletes keys past their expiry (spec §19: cleanup after a documented window). Returns how many. */
    int deleteExpired(Instant now, int batchSize);
}
