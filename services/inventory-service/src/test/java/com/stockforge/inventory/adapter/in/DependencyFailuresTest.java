package com.stockforge.inventory.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class DependencyFailuresTest {

    @Test
    void outagesSeenInTheChaosTestsAreUnavailable() {
        assertThat(DependencyFailures.isUnavailable(new DataAccessResourceFailureException("I/O error")))
                .isTrue();
        assertThat(DependencyFailures.isUnavailable(new TransactionSystemException("JDBC rollback failed")))
                .isTrue();
        assertThat(DependencyFailures.isUnavailable(
                        new CannotGetJdbcConnectionException("pool exhausted", new SQLException("timeout"))))
                .isTrue();
        assertThat(DependencyFailures.isUnavailable(new CannotCreateTransactionException("no connection")))
                .isTrue();
        assertThat(DependencyFailures.isUnavailable(new QueryTimeoutException("statement timeout")))
                .isTrue();
    }

    @Test
    void aLockTimeoutOnAHotRowIsContentionNotABug() {
        // What Spring surfaces when lock_timeout expires: SQLSTATE 55P03 is not translated to a transient exception.
        var lockTimeout = new UncategorizedSQLException(
                "PreparedStatementCallback",
                "UPDATE inventory SET available_quantity = available_quantity - ?",
                new SQLException("ERROR: canceling statement due to lock timeout", "55P03"));

        assertThat(DependencyFailures.isUnavailable(lockTimeout)).isTrue();
        assertThat(DependencyFailures.isUnavailable(
                        new UncategorizedSQLException("x", "y", new SQLException("terminating connection", "57P01"))))
                .isTrue();
    }

    @Test
    void bugsAndConstraintViolationsAreNot() {
        assertThat(DependencyFailures.isUnavailable(new DataIntegrityViolationException("duplicate key")))
                .isFalse();
        assertThat(DependencyFailures.isUnavailable(new IllegalStateException("bug")))
                .isFalse();
        assertThat(DependencyFailures.isUnavailable(
                        new UncategorizedSQLException("x", "y", new SQLException("syntax error", "42601"))))
                .isFalse();
    }
}
