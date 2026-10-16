package com.stockforge.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deletes published outbox rows and old inbox rows so both tables and their indexes stay small (spec §20, §33 data
 * retention). Deletes in bounded batches so no single statement holds locks for long.
 */
final class MessagingRetention {

    private static final int BATCH = 5_000;

    private final JdbcClient jdbc;
    private final MessagingProperties properties;
    private final boolean outboxEnabled;

    MessagingRetention(JdbcClient jdbc, MessagingProperties properties, boolean outboxEnabled) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.outboxEnabled = outboxEnabled;
    }

    int purge() {
        int deleted = 0;
        if (outboxEnabled) {
            deleted += deleteInBatches("""
                    DELETE FROM outbox_events
                    WHERE id IN (SELECT id FROM outbox_events
                                 WHERE status = 'PUBLISHED' AND published_at < now() - make_interval(secs => :age)
                                 LIMIT :batch)
                    """, properties.outbox().retention().toSeconds());
        }
        deleted += deleteInBatches("""
                DELETE FROM processed_events
                WHERE (consumer_group, event_id) IN (
                    SELECT consumer_group, event_id FROM processed_events
                    WHERE processed_at < now() - make_interval(secs => :age)
                    LIMIT :batch)
                """, properties.inbox().retention().toSeconds());
        return deleted;
    }

    private int deleteInBatches(String sql, long ageSeconds) {
        int total = 0;
        int deleted;
        do {
            deleted =
                    jdbc.sql(sql).param("age", ageSeconds).param("batch", BATCH).update();
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
