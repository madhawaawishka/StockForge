package com.stockforge.gateway.adapter.out.redis;

import com.stockforge.gateway.domain.model.RateLimitResult;
import com.stockforge.gateway.domain.port.RateLimiter;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Atomic token-bucket rate limiter implemented via Redis Lua script (spec §17, §23 C). */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'last_updated')
            local tokens = tonumber(data[1])
            local last_updated = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last_updated = now
            else
                local delta = math.max(0, now - last_updated)
                tokens = math.min(capacity, tokens + delta * refill_rate)
                last_updated = now
            end

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
                redis.call('EXPIRE', key, math.ceil(capacity / refill_rate) + 60)
                return {1, math.floor(tokens), 0}
            else
                local missing = requested - tokens
                local retry_after = math.ceil(missing / refill_rate)
                redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
                redis.call('EXPIRE', key, math.ceil(capacity / refill_rate) + 60)
                return {0, math.floor(tokens), retry_after}
            end
            """;

    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;

    private final int defaultCapacity;
    private final int defaultRefillRate;
    private final boolean failOpen;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, 100, 10, true);
    }

    @SuppressWarnings("rawtypes")
    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate, int defaultCapacity, int defaultRefillRate, boolean failOpen) {
        this.redisTemplate = redisTemplate;
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.failOpen = failOpen;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA_SCRIPT);
        this.script.setResultType(List.class);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokensRequested) {
        if (redisTemplate == null) {
            return RateLimitResult.allowed(defaultCapacity);
        }

        try {
            long nowEpochSeconds = Instant.now().getEpochSecond();
            String redisKey = "rate_limit:" + key;
            List<?> result = redisTemplate.execute(
                    script,
                    Collections.singletonList(redisKey),
                    String.valueOf(defaultCapacity),
                    String.valueOf(defaultRefillRate),
                    String.valueOf(tokensRequested),
                    String.valueOf(nowEpochSeconds));

            if (result != null && result.size() >= 3) {
                long allowedFlag = ((Number) result.get(0)).longValue();
                long remaining = ((Number) result.get(1)).longValue();
                long retryAfter = ((Number) result.get(2)).longValue();

                if (allowedFlag == 1L) {
                    return RateLimitResult.allowed(remaining);
                } else {
                    return RateLimitResult.rejected(retryAfter);
                }
            }
            return RateLimitResult.allowed(defaultCapacity);
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable: {}. Fail-open policy={}", e.getMessage(), failOpen);
            return failOpen ? RateLimitResult.allowed(defaultCapacity) : RateLimitResult.rejected(1);
        }
    }
}
