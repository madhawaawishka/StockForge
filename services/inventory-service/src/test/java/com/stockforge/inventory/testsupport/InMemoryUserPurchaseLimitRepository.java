package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Fake {@link UserPurchaseLimitRepository} for unit tests. */
public class InMemoryUserPurchaseLimitRepository implements UserPurchaseLimitRepository {

    private record LimitKey(UserId userId, ProductId productId) {}

    private final Map<LimitKey, Integer> limits = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(
            UserId userId, ProductId productId, int quantity, int maxLimit, Instant now) {
        LimitKey key = new LimitKey(userId, productId);
        int current = limits.getOrDefault(key, 0);
        if (current + quantity > maxLimit) {
            return false;
        }
        limits.put(key, current + quantity);
        return true;
    }

    @Override
    public synchronized void release(UserId userId, ProductId productId, int quantity, Instant now) {
        LimitKey key = new LimitKey(userId, productId);
        int current = limits.getOrDefault(key, 0);
        limits.put(key, Math.max(0, current - quantity));
    }
}
