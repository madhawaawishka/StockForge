package com.stockforge.gateway.domain.port;

import com.stockforge.gateway.domain.model.RateLimitResult;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key, int tokensRequested);
}
