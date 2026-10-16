package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Fake {@link UserPurchaseLimitRepository}; fails like the database CHECK constraint on a negative counter. */
public class InMemoryUserPurchaseLimitRepository implements UserPurchaseLimitRepository {

    private record LimitKey(UserId userId, ProductId productId) {}

    private final Map<LimitKey, Integer> held = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(
            UserId userId, ProductId productId, int quantity, int maxLimit, Instant now) {
        LimitKey key = new LimitKey(userId, productId);
        int current = held.getOrDefault(key, 0);
        if (current + quantity > maxLimit) {
            return false;
        }
        held.put(key, current + quantity);
        return true;
    }

    @Override
    public synchronized void release(UserId userId, ProductId productId, int quantity, Instant now) {
        LimitKey key = new LimitKey(userId, productId);
        int remaining = held.getOrDefault(key, 0) - quantity;
        if (remaining < 0) {
            throw new IllegalStateException("Purchase-limit counter would become negative for " + key);
        }
        held.put(key, remaining);
    }

    public synchronized int heldBy(UserId userId, ProductId productId) {
        return held.getOrDefault(new LimitKey(userId, productId), 0);
    }
}
