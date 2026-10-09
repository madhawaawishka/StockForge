package com.stockforge.inventory.adapter.out.persistence;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Executes database operations with bounded retries on retryable PostgreSQL SQLSTATE errors (spec §14 F).
 *
 * <p>SQLSTATE 40001 (serialization_failure) and 40P01 (deadlock_detected) are transient concurrency conflicts that
 * should be retried with jittered exponential backoff. Non-retryable errors (e.g. constraint violations, bad syntax)
 * fail immediately without retrying.
 */
@Component
class PostgresRetryTemplate {

    public static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";
    public static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    public <T> T execute(Supplier<T> action) {
        return execute(action, DEFAULT_MAX_ATTEMPTS);
    }

    public <T> T execute(Supplier<T> action, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw ex;
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    public static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (SQLSTATE_SERIALIZATION_FAILURE.equals(sqlState) || SQLSTATE_DEADLOCK_DETECTED.equals(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void backoff(int attempt) {
        try {
            int delayMs = (attempt * 10) + ThreadLocalRandom.current().nextInt(10);
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry backoff interrupted", ie);
        }
    }
}
