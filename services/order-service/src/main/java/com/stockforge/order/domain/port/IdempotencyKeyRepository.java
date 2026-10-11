package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository {

    record IdempotencyRecord(
            String key,
            UserId userId,
            String requestHash,
            String status,
            Integer responseStatus,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt) {

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }

        public boolean isInProgress() {
            return "IN_PROGRESS".equals(status);
        }
    }

    boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt);

    void complete(String key, UserId userId, int responseStatus, String responsePayload, Instant now);

    Optional<IdempotencyRecord> find(String key, UserId userId);
}
