package com.stockforge.inventory.adapter.in;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Decides whether a failure means "a dependency is temporarily unavailable" (spec §23 D), so every inbound adapter
 * answers it the same way: 503 + Retry-After over REST, {@code UNAVAILABLE} over gRPC, never a generic internal error.
 *
 * <p>The list comes from the chaos tests (W5-06): a black-holed database surfaces as a
 * {@link DataAccessResourceFailureException} (SQLSTATE class 08) while the statement runs, and as a
 * {@link TransactionSystemException} when the rollback on the dead connection fails too and replaces the original
 * exception. Both are safe to retry because every write is idempotent (spec §15).
 */
public final class DependencyFailures {

    /**
     * SQLSTATEs that mean "the database is busy or going away", whatever exception Spring wrapped them in. Spring does
     * not translate {@code 55P03} at all (it surfaces as an {@code UncategorizedSQLException}); a lock that could not
     * be taken within {@code lock_timeout} is contention, not a bug, and must be answered as retryable.
     */
    private static final Set<String> BUSY_SQL_STATES = Set.of(
            "55P03", // lock_not_available: lock_timeout expired, typically on a hot inventory row
            "57014", // query_canceled: statement_timeout expired
            "53300", // too_many_connections
            "57P01", // admin_shutdown: the server is restarting or failing over
            "57P03"); // cannot_connect_now: the server is starting up

    private DependencyFailures() {}

    public static boolean isUnavailable(Throwable failure) {
        return failure instanceof TransientDataAccessException // timeouts, lock and serialization failures
                || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException // includes CannotGetJdbcConnectionException
                || failure instanceof CannotCreateTransactionException
                || failure instanceof TransactionSystemException
                || failure instanceof TransactionTimedOutException
                || busySqlState(failure);
    }

    private static boolean busySqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sql.getSQLState() != null
                    && (BUSY_SQL_STATES.contains(sql.getSQLState())
                            || sql.getSQLState().startsWith("08"))) {
                return true;
            }
        }
        return false;
    }
}
