package com.stockforge.gateway.adapter.out.redis;

import com.stockforge.gateway.config.GatewayProperties;
import com.stockforge.gateway.domain.port.RateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiter in Redis (spec §23 C). The check-and-take runs as one Lua script, so it is atomic across
 * all gateway instances, and it uses Redis server time, so gateways with skewed clocks agree on refills.
 *
 * <p>When Redis is unreachable the limiter follows {@code gateway.rate-limit.fail-open} (ADR-010) behind the
 * {@code rateLimiter} circuit breaker, so an outage costs one fast failed call per cool-down rather than a timeout per
 * request. Decisions are counted in {@code stockforge_rate_limit_decisions_total{policy,outcome}}; every policy/outcome
 * series exists from start-up at zero, so the first fail-open decision is visible to {@code rate()} and to the
 * {@code RateLimiterDegraded} alert.
 */
@Component
class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /** Returns {allowed (1/0), remaining tokens, retry-after seconds}. Raw List: Redis returns untyped arrays. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2]) / 1000
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local state = redis.call('HMGET', key, 'tokens', 'updated')
            local tokens = tonumber(state[1]) or capacity
            local updated = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - updated) * refill_per_ms)

            local allowed = 0
            local retry_after = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retry_after = math.ceil((1 - tokens) / refill_per_ms / 1000)
            end
            redis.call('HSET', key, 'tokens', tokens, 'updated', now)
            redis.call('PEXPIRE', key, math.ceil(capacity / refill_per_ms) + 60000)
            return {allowed, math.floor(tokens), retry_after}
            """, List.class);

    private static final List<String> OUTCOMES = List.of("allowed", "rejected", "fail_open", "fail_closed");

    private final StringRedisTemplate redis;
    private final GatewayProperties.RateLimit config;
    private final CircuitBreaker breaker;
    private final MeterRegistry meters;

    RedisTokenBucketRateLimiter(
            StringRedisTemplate redis,
            GatewayProperties properties,
            CircuitBreakerRegistry breakers,
            MeterRegistry meters) {
        this.redis = redis;
        this.config = properties.rateLimit();
        this.breaker = breakers.circuitBreaker("rateLimiter");
        this.meters = meters;
        config.policies().keySet().forEach(policy -> OUTCOMES.forEach(outcome -> counter(policy, outcome)));
    }

    @Override
    public Decision tryAcquire(String policy, String key) {
        GatewayProperties.Bucket bucket = config.policies().get(policy);
        if (bucket == null) {
            throw new IllegalArgumentException("No rate limit policy named " + policy);
        }
        Decision decision;
        try {
            List<?> result = breaker.executeSupplier(() -> redis.execute(
                    TOKEN_BUCKET,
                    List.of("stockforge:rate-limit:" + policy + ":" + key),
                    String.valueOf(bucket.capacity()),
                    String.valueOf(bucket.refillPerSecond())));
            decision = ((Number) result.get(0)).longValue() == 1
                    ? Decision.allow(((Number) result.get(1)).longValue())
                    : Decision.reject(((Number) result.get(2)).longValue());
            count(policy, decision.allowed() ? "allowed" : "rejected");
        } catch (RuntimeException unavailable) {
            log.debug("Rate limiter unavailable, failing {}", config.failOpen() ? "open" : "closed", unavailable);
            count(policy, config.failOpen() ? "fail_open" : "fail_closed");
            decision = config.failOpen() ? Decision.allow(bucket.capacity()) : Decision.reject(1);
        }
        return decision;
    }

    private void count(String policy, String outcome) {
        counter(policy, outcome).increment();
    }

    private Counter counter(String policy, String outcome) {
        return Counter.builder("stockforge.rate.limit.decisions")
                .description("Rate limiter decisions")
                .tag("policy", policy)
                .tag("outcome", outcome)
                .register(meters);
    }
}
