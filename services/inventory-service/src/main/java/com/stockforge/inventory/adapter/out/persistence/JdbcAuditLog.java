package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.domain.port.AuditLog;
import com.stockforge.inventory.domain.port.IdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IdGenerator ids;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void record(
            String actorId, String action, String aggregateType, String aggregateId, Map<String, Object> details) {
        jdbc.sql("""
                        INSERT INTO audit_logs (id, actor_id, action, aggregate_type, aggregate_id, metadata, created_at)
                        VALUES (:id, :actorId, :action, :aggregateType, :aggregateId,
                                CAST(:metadata AS JSONB), :createdAt)
                        """)
                .param("id", ids.newId())
                .param("actorId", actorId)
                .param("action", action)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("metadata", json.writeValueAsString(details))
                .param(
                        "createdAt",
                        clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .update();
    }
}
