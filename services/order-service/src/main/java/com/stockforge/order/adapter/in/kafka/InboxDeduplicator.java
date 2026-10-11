package com.stockforge.order.adapter.in.kafka;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Idempotent consumer inbox helper ensuring at-most-once processing semantics (spec §18). */
@Component
public class InboxDeduplicator {

    private final JdbcClient jdbc;

    public InboxDeduplicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String consumerGroup, String eventId, Instant now) {
        try {
            int rows = jdbc.sql("""
                            INSERT INTO processed_events (consumer_group, event_id, processed_at)
                            VALUES (:group, :eventId, :processedAt)
                            ON CONFLICT (consumer_group, event_id) DO NOTHING
                            """)
                    .param("group", consumerGroup)
                    .param("eventId", eventId)
                    .param("processedAt", now.atOffset(ZoneOffset.UTC))
                    .update();
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
