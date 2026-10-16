package com.stockforge.gateway.domain.port;

/** Per-client request budget (spec §23 C). */
public interface RateLimiter {

    /**
     * Takes one token from {@code key}'s bucket of the given policy.
     *
     * @param policy named limit, for example {@code reservations}
     */
    Decision tryAcquire(String policy, String key);

    /**
     * Outcome of one attempt.
     *
     * @param retryAfterSeconds when a rejected client should try again
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {

        public static Decision allow(long remaining) {
            return new Decision(true, remaining, 0);
        }

        public static Decision reject(long retryAfterSeconds) {
            return new Decision(false, 0, Math.max(1, retryAfterSeconds));
        }
    }
}
