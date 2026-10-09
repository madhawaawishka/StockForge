package com.stockforge.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Idempotency record ensuring safe request retries (spec §19). */
public record IdempotencyRecord(
        String key,
        UserId userId,
        String requestHash,
        String status,
        Integer responseStatus,
        String responsePayload,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public boolean isInProgress() {
        return "IN_PROGRESS".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
