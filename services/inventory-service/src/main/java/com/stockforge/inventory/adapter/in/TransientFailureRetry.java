package com.stockforge.inventory.adapter.in;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a whole use case — that is, its whole transaction — when PostgreSQL aborted it with a serialization failure
 * ({@code 40001}) or a deadlock ({@code 40P01}) (spec §14 F).
 *
 * <p>Inbound adapters wrap the use-case call, so every attempt is a fresh transaction. Only those two SQLSTATEs are
 * retried: a constraint violation or a lock timeout would fail the same way again and retrying would only add load.
 * Kafka consumers do not use this: their error handler already retries the whole message (spec §22: retry at one layer
 * only).
 */
public final class TransientFailureRetry {

    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED = "40P01";

    private final int maxAttempts;

    public TransientFailureRetry(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    public <T> T execute(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (RuntimeException failure) {
                if (attempt >= maxAttempts || !isRetryable(failure)) {
                    throw failure;
                }
                backOff(attempt);
            }
        }
    }

    public static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (SERIALIZATION_FAILURE.equals(sql.getSQLState())
                            || DEADLOCK_DETECTED.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /** Exponential backoff with full jitter, so retrying transactions do not collide again in lockstep. */
    private static void backOff(int attempt) {
        long ceilingMillis = 10L << Math.min(attempt, 6);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceilingMillis + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a transaction", interrupted);
        }
    }
}
