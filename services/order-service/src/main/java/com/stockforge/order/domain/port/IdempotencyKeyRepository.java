package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

/** Idempotency keys of order creation, stored in the order's transaction (spec §19). Keys are scoped per user. */
public interface IdempotencyKeyRepository {

    /** A stored key and the outcome of the request that first used it. */
    record IdempotencyRecord(String key, UserId userId, String requestHash, Integer responseStatus, String response) {}

    /** Claims the key; a concurrent request with the same key waits for the first transaction, then gets false. */
    boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt);

    Optional<IdempotencyRecord> find(String key, UserId userId);

    void complete(String key, UserId userId, int responseStatus, String response);

    int deleteExpired(Instant now, int batchSize);
}
