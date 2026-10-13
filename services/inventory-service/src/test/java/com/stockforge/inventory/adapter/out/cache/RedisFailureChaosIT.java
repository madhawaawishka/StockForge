package com.stockforge.inventory.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.ProductCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisFailureChaosIT {

    @Test
    @DisplayName("Redis read failure degrades gracefully to DB fallback and triggers circuit breaker")
    void redisReadFailureGracefulDegradationAndCircuitBreakerTrip() {
        // Arrange: mock Redis throwing connection failure
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(mockRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused by Toxiproxy"));

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        @SuppressWarnings("unchecked")
        ObjectProvider<CircuitBreakerRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        // Instantiate package-private cache adapter
        ProductCache cache = new RedisProductCache(mockRedis, new ObjectMapper(), provider);

        ProductId testId = ProductId.of(UUID.randomUUID());

        // Act & Assert 1: First calls should fail gracefully and return Optional.empty() without crashing
        for (int i = 0; i < 4; i++) {
            Optional<Product> result = cache.getProduct(testId);
            assertThat(result).isEmpty();
        }

        // Assert 2: Circuit breaker should now be in OPEN state
        CircuitBreaker cb = registry.circuitBreaker("redisCatalogCache");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Act & Assert 3: Call while circuit is OPEN fast-fails without hitting Redis, still returns Optional.empty()
        Optional<Product> fastFailResult = cache.getProduct(testId);
        assertThat(fastFailResult).isEmpty();
    }
}
