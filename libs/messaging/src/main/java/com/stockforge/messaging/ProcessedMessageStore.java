package com.stockforge.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The inbox of an idempotent consumer (spec §18): remembers which event ids a consumer group has applied.
 *
 * <p>{@link #markProcessed} must run in the same transaction as the business change it guards. Then the change and the
 * inbox row commit together: a redelivery after a crash finds the row and skips, while a failed attempt rolls the row
 * back so the retry is not mistaken for a duplicate.
 */
public final class ProcessedMessageStore {

    private final JdbcClient jdbc;

    ProcessedMessageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as processed by {@code consumerGroup}.
     *
     * @return {@code true} the first time, {@code false} if the event was already processed (a duplicate delivery)
     */
    public boolean markProcessed(String consumerGroup, UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The inbox row must commit together with the business change");
        }
        // A concurrent duplicate blocks on the unique key until the first transaction ends, then inserts nothing.
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer_group, event_id, processed_at)
                        VALUES (:consumerGroup, :eventId, now())
                        ON CONFLICT (consumer_group, event_id) DO NOTHING
                        """)
                        .param("consumerGroup", consumerGroup)
                        .param("eventId", eventId)
                        .update()
                == 1;
    }
}
