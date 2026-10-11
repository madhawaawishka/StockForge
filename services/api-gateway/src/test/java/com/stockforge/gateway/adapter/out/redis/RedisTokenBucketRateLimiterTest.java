package com.stockforge.gateway.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.gateway.domain.model.RateLimitResult;
import org.junit.jupiter.api.Test;

class RedisTokenBucketRateLimiterTest {

    @Test
    void failsOpenWhenRedisTemplateIsNull() {
        // Spec §17: Fallback behavior when cache/Redis is unavailable
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(null, 100, 10, true);
        RateLimitResult result = limiter.tryAcquire("user:123", 1);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(100);
    }
}
