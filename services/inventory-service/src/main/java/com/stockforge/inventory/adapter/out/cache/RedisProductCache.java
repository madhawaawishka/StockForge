package com.stockforge.inventory.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.ProductCache;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed cache-aside implementation with stampede protection and jittered TTL (spec §17). */
@Component
class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);

    private static final String PRODUCT_KEY_PREFIX = "stockforge:product:";
    private static final String INVENTORY_KEY_PREFIX = "stockforge:inventory:";
    private static final int PRODUCT_BASE_TTL_SECONDS = 300;
    private static final int PRODUCT_JITTER_SECONDS = 60;
    private static final int INVENTORY_BASE_TTL_SECONDS = 2;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    RedisProductCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Product> getProduct(ProductId id) {
        String key = PRODUCT_KEY_PREFIX + id.value();
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserializeProduct(json));
        } catch (Exception e) {
            log.warn("Redis read failure for key {}: {}. Falling back to DB.", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putProduct(Product product) {
        String key = PRODUCT_KEY_PREFIX + product.id().value();
        try {
            String json = serializeProduct(product);
            int jitter = ThreadLocalRandom.current().nextInt(PRODUCT_JITTER_SECONDS + 1);
            Duration ttl = Duration.ofSeconds((long) PRODUCT_BASE_TTL_SECONDS + jitter);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("Redis write failure for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictProduct(ProductId id) {
        String key = PRODUCT_KEY_PREFIX + id.value();
        try {
            redis.delete(key);
            log.debug("Evicted cache for product {}", id.value());
        } catch (Exception e) {
            log.warn("Redis evict failure for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public Optional<Inventory> getInventory(ProductId id) {
        String key = INVENTORY_KEY_PREFIX + id.value();
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserializeInventory(json));
        } catch (Exception e) {
            log.warn("Redis read failure for inventory key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putInventory(Inventory inventory) {
        String key = INVENTORY_KEY_PREFIX + inventory.productId().value();
        try {
            String json = serializeInventory(inventory);
            Duration ttl = Duration.ofSeconds(INVENTORY_BASE_TTL_SECONDS);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("Redis write failure for inventory key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictInventory(ProductId id) {
        String key = INVENTORY_KEY_PREFIX + id.value();
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis evict failure for inventory key {}: {}", key, e.getMessage());
        }
    }

    private String serializeProduct(Product product) throws JsonProcessingException {
        ProductCacheDto dto = new ProductCacheDto(
                product.id().value().toString(),
                product.sku().value(),
                product.name(),
                product.price().amount().toPlainString(),
                product.price().currency().getCurrencyCode(),
                product.status().name(),
                product.createdAt().toString(),
                product.updatedAt().toString());
        return objectMapper.writeValueAsString(dto);
    }

    private Product deserializeProduct(String json) throws IOException {
        ProductCacheDto dto = objectMapper.readValue(json, ProductCacheDto.class);
        return new Product(
                ProductId.of(UUID.fromString(dto.id())),
                new Sku(dto.sku()),
                dto.name(),
                Money.of(dto.priceAmount(), dto.currency()),
                ProductStatus.valueOf(dto.status()),
                Instant.parse(dto.createdAt()),
                Instant.parse(dto.updatedAt()));
    }

    private String serializeInventory(Inventory inventory) throws JsonProcessingException {
        InventoryCacheDto dto = new InventoryCacheDto(
                inventory.productId().value().toString(),
                inventory.totalQuantity(),
                inventory.availableQuantity(),
                inventory.reservedQuantity(),
                inventory.soldQuantity(),
                inventory.version(),
                inventory.updatedAt().toString());
        return objectMapper.writeValueAsString(dto);
    }

    private Inventory deserializeInventory(String json) throws IOException {
        InventoryCacheDto dto = objectMapper.readValue(json, InventoryCacheDto.class);
        return new Inventory(
                ProductId.of(UUID.fromString(dto.productId())),
                dto.totalQuantity(),
                dto.availableQuantity(),
                dto.reservedQuantity(),
                dto.soldQuantity(),
                dto.version(),
                Instant.parse(dto.updatedAt()));
    }

    private record ProductCacheDto(
            String id,
            String sku,
            String name,
            String priceAmount,
            String currency,
            String status,
            String createdAt,
            String updatedAt) {}

    private record InventoryCacheDto(
            String productId,
            int totalQuantity,
            int availableQuantity,
            int reservedQuantity,
            int soldQuantity,
            long version,
            String updatedAt) {}
}
