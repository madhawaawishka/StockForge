package com.stockforge.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

class PostgresRetryTemplateTest {

    private final PostgresRetryTemplate retryTemplate = new PostgresRetryTemplate();

    @Test
    void succeedsWithoutRetryWhenActionSucceeds() {
        String result = retryTemplate.execute(() -> "success");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void retriesOnSerializationFailureAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ConcurrencyFailureException(
                        "Serialization conflict",
                        new SQLException(
                                "could not serialize access", PostgresRetryTemplate.SQLSTATE_SERIALIZATION_FAILURE));
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retriesOnDeadlockDetectedAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new ConcurrencyFailureException(
                        "Deadlock detected",
                        new SQLException("deadlock detected", PostgresRetryTemplate.SQLSTATE_DEADLOCK_DETECTED));
            }
            return "recovered-from-deadlock";
        });

        assertThat(result).isEqualTo("recovered-from-deadlock");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void failsImmediatelyOnNonRetryableSqlState() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(() -> {
                    attempts.incrementAndGet();
                    throw new DataIntegrityViolationException(
                            "Unique constraint violated", new SQLException("duplicate key", "23505"));
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void throwsWhenMaxAttemptsExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(() -> {
                    attempts.incrementAndGet();
                    throw new ConcurrencyFailureException(
                            "Persistent serialization conflict",
                            new SQLException(
                                    "could not serialize access",
                                    PostgresRetryTemplate.SQLSTATE_SERIALIZATION_FAILURE));
                }))
                .isInstanceOf(ConcurrencyFailureException.class);

        assertThat(attempts.get()).isEqualTo(PostgresRetryTemplate.DEFAULT_MAX_ATTEMPTS);
    }
}
