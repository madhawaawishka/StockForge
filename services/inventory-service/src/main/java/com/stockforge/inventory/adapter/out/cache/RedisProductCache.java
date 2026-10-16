package com.stockforge.inventory.adapter.out.cache;

import com.stockforge.inventory.config.InventoryProperties;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.ProductCache;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside product cache in Redis (spec §17).
 *
 * <ul>
 *   <li><b>Redis is optional (NFR-9).</b> Every call has a short command timeout ({@code spring.data.redis.timeout})
 *       and goes through the {@code productCache} circuit breaker. A failure or an open breaker is a cache miss, so
 *       reads fall back to PostgreSQL and never fail because of Redis.
 *   <li><b>Jittered TTLs</b> keep products cached together from expiring together.
 *   <li><b>Invalidation after commit</b> by deleting, never overwriting, the key.
 *   <li><b>Metrics:</b> {@code stockforge_cache_requests_total{cache,result}} gives the hit ratio.
 * </ul>
 */
@Component
class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);

    private static final String PRODUCT_KEY = "stockforge:product:";
    private static final String INVENTORY_KEY = "stockforge:availability:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CircuitBreaker breaker;
    private final InventoryProperties.Cache config;
    private final MeterRegistry meters;

    RedisProductCache(
            StringRedisTemplate redis,
            JsonMapper json,
            CircuitBreakerRegistry breakers,
            InventoryProperties properties,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.breaker = breakers.circuitBreaker("productCache");
        this.config = properties.cache();
        this.meters = meters;
    }

    @Override
    public Optional<Product> getProduct(ProductId id) {
        return read("product", PRODUCT_KEY + id.value())
                .flatMap(value -> decode(() -> toProduct(json.readValue(value, CachedProduct.class))));
    }

    @Override
    public void putProduct(Product product) {
        long jitterMillis =
                ThreadLocalRandom.current().nextLong(config.productTtlJitter().toMillis() + 1);
        write(
                PRODUCT_KEY + product.id().value(),
                json.writeValueAsString(CachedProduct.from(product)),
                config.productTtl().plusMillis(jitterMillis));
    }

    @Override
    public void evictProduct(ProductId id) {
        evictAfterCommit(PRODUCT_KEY + id.value());
    }

    @Override
    public Optional<Inventory> getInventory(ProductId id) {
        return read("availability", INVENTORY_KEY + id.value())
                .flatMap(value -> decode(() -> toInventory(json.readValue(value, CachedInventory.class))));
    }

    @Override
    public void putInventory(Inventory inventory) {
        write(
                INVENTORY_KEY + inventory.productId().value(),
                json.writeValueAsString(CachedInventory.from(inventory)),
                config.availabilityTtl());
    }

    @Override
    public void evictInventory(ProductId id) {
        evictAfterCommit(INVENTORY_KEY + id.value());
    }

    private Optional<String> read(String cache, String key) {
        Optional<String> value = guarded(() -> redis.opsForValue().get(key), "read " + key);
        count(cache, value.isPresent() ? "hit" : "miss");
        return value;
    }

    private void write(String key, String value, Duration ttl) {
        Optional<Boolean> unused = guarded(
                () -> {
                    redis.opsForValue().set(key, value, ttl);
                    return Boolean.TRUE;
                },
                "write " + key);
    }

    /**
     * Deleting after commit avoids caching a value that is then rolled back; deleting rather than writing the new value
     * avoids racing a concurrent writer (spec §17).
     */
    private void evictAfterCommit(String key) {
        Runnable delete = () -> guarded(() -> redis.delete(key), "delete " + key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    private <T> Optional<T> guarded(Supplier<T> call, String what) {
        try {
            return Optional.ofNullable(breaker.executeSupplier(call));
        } catch (CallNotPermittedException open) {
            return Optional.empty();
        } catch (RuntimeException unavailable) {
            // Expected while Redis is down; the breaker opens after a few failures and stops the log noise.
            log.debug("Redis {} failed: {}", what, unavailable.toString());
            return Optional.empty();
        }
    }

    /** An entry written by an incompatible older version is treated as a miss and overwritten by the reload. */
    private static <T> Optional<T> decode(Supplier<T> decoder) {
        try {
            return Optional.of(decoder.get());
        } catch (RuntimeException unreadable) {
            log.warn("Ignoring unreadable cache entry: {}", unreadable.toString());
            return Optional.empty();
        }
    }

    private void count(String cache, String result) {
        Counter.builder("stockforge.cache.requests")
                .description("Cache lookups by result")
                .tag("cache", cache)
                .tag("result", result)
                .register(meters)
                .increment();
    }

    private static Product toProduct(CachedProduct cached) {
        return new Product(
                ProductId.of(UUID.fromString(cached.id())),
                Sku.of(cached.sku()),
                cached.name(),
                Money.of(cached.priceAmount(), cached.currency()),
                ProductStatus.valueOf(cached.status()),
                Instant.parse(cached.createdAt()),
                Instant.parse(cached.updatedAt()));
    }

    private static Inventory toInventory(CachedInventory cached) {
        return new Inventory(
                ProductId.of(UUID.fromString(cached.productId())),
                cached.total(),
                cached.available(),
                cached.reserved(),
                cached.sold(),
                cached.version(),
                Instant.parse(cached.updatedAt()));
    }

    /** Cache representation, decoupled from the domain records so they can evolve independently. */
    record CachedProduct(
            String id,
            String sku,
            String name,
            String priceAmount,
            String currency,
            String status,
            String createdAt,
            String updatedAt) {

        static CachedProduct from(Product product) {
            return new CachedProduct(
                    product.id().toString(),
                    product.sku().value(),
                    product.name(),
                    product.price().amount().toPlainString(),
                    product.price().currency().getCurrencyCode(),
                    product.status().name(),
                    product.createdAt().toString(),
                    product.updatedAt().toString());
        }
    }

    record CachedInventory(
            String productId, int total, int available, int reserved, int sold, long version, String updatedAt) {

        static CachedInventory from(Inventory inventory) {
            return new CachedInventory(
                    inventory.productId().toString(),
                    inventory.totalQuantity(),
                    inventory.availableQuantity(),
                    inventory.reservedQuantity(),
                    inventory.soldQuantity(),
                    inventory.version(),
                    inventory.updatedAt().toString());
        }
    }
}
