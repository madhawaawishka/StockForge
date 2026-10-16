package com.stockforge.inventory.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

class TransientFailureRetryTest {

    private final TransientFailureRetry retry = new TransientFailureRetry(3);

    @Test
    void retriesASerializationFailureUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw failure(TransientFailureRetry.SERIALIZATION_FAILURE);
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void retriesADeadlock() {
        AtomicInteger attempts = new AtomicInteger();

        retry.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw failure(TransientFailureRetry.DEADLOCK_DETECTED);
            }
            return null;
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterTheConfiguredAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw failure(TransientFailureRetry.SERIALIZATION_FAILURE);
                }))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void neverRetriesAConstraintViolation() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new DataIntegrityViolationException(
                            "duplicate key", new SQLException("duplicate key", "23505"));
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void neverRetriesALockTimeout() {
        // 55P03 lock_not_available: retrying would queue on the same hot row again and multiply load.
        assertThat(TransientFailureRetry.isRetryable(new CannotAcquireLockException(
                        "lock timeout", new SQLException("canceling statement due to lock timeout", "55P03"))))
                .isFalse();
    }

    private static CannotAcquireLockException failure(String sqlState) {
        return new CannotAcquireLockException("aborted", new SQLException("aborted", sqlState));
    }
}
