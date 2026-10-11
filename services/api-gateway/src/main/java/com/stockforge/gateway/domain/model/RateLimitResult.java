package com.stockforge.gateway.domain.model;

public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterSeconds) {

    public static RateLimitResult allowed(long remainingTokens) {
        return new RateLimitResult(true, remainingTokens, 0);
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return new RateLimitResult(false, 0, Math.max(1, retryAfterSeconds));
    }
}
