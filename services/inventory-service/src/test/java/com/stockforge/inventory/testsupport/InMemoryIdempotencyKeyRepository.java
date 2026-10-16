package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fake {@link IdempotencyKeyRepository} for unit tests. */
public class InMemoryIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private record KeyScope(String key, UserId userId) {}

    private final Map<KeyScope, IdempotencyRecord> records = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(
            String key, UserId userId, String requestHash, Instant now, Instant expiresAt) {
        KeyScope scope = new KeyScope(key, userId);
        if (records.containsKey(scope)) {
            return false;
        }
        records.put(scope, new IdempotencyRecord(key, userId, requestHash, "IN_PROGRESS", null, null, now, expiresAt));
        return true;
    }

    @Override
    public synchronized Optional<IdempotencyRecord> findByKeyAndUser(String key, UserId userId) {
        return Optional.ofNullable(records.get(new KeyScope(key, userId)));
    }

    @Override
    public synchronized void complete(String key, UserId userId, int responseStatus, String responsePayload) {
        KeyScope scope = new KeyScope(key, userId);
        IdempotencyRecord current = records.get(scope);
        if (current != null) {
            records.put(
                    scope,
                    new IdempotencyRecord(
                            key,
                            userId,
                            current.requestHash(),
                            "COMPLETED",
                            responseStatus,
                            responsePayload,
                            current.createdAt(),
                            current.expiresAt()));
        }
    }

    @Override
    public synchronized void release(String key, UserId userId) {
        records.remove(new KeyScope(key, userId));
    }

    @Override
    public synchronized int deleteExpired(Instant now, int batchSize) {
        List<KeyScope> expired = records.entrySet().stream()
                .filter(entry -> entry.getValue().expiresAt().isBefore(now))
                .map(Map.Entry::getKey)
                .limit(batchSize)
                .toList();
        expired.forEach(records::remove);
        return expired.size();
    }
}
