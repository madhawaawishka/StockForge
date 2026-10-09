package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt) {
        int rows = jdbc.sql("""
                        INSERT INTO idempotency_keys (key, user_id, request_hash, status, created_at, expires_at)
                        VALUES (:key, :userId, :requestHash, 'IN_PROGRESS', :createdAt, :expiresAt)
                        ON CONFLICT (user_id, key) DO NOTHING
                        """)
                .param("key", key)
                .param("userId", userId.value())
                .param("requestHash", requestHash)
                .param("createdAt", toTimestamp(now))
                .param("expiresAt", toTimestamp(expiresAt))
                .update();
        return rows == 1;
    }

    @Override
    public Optional<IdempotencyRecord> findByKeyAndUser(String key, UserId userId) {
        return jdbc.sql("""
                        SELECT key, user_id, request_hash, status, response_status, response_payload, created_at, expires_at
                        FROM idempotency_keys
                        WHERE key = :key AND user_id = :userId
                        """)
                .param("key", key)
                .param("userId", userId.value())
                .query(JdbcIdempotencyKeyRepository::mapRow)
                .optional();
    }

    @Override
    public void complete(String key, UserId userId, int responseStatus, String responsePayload) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = :status, response_payload = :payload
                        WHERE key = :key AND user_id = :userId
                        """)
                .param("key", key)
                .param("userId", userId.value())
                .param("status", responseStatus)
                .param("payload", responsePayload)
                .update();
    }

    @Override
    public void fail(String key, UserId userId) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'FAILED'
                        WHERE key = :key AND user_id = :userId
                        """).param("key", key).param("userId", userId.value()).update();
    }

    private static IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer responseStatus = (Integer) rs.getObject("response_status");
        return new IdempotencyRecord(
                rs.getString("key"),
                UserId.of(rs.getObject("user_id", UUID.class)),
                rs.getString("request_hash"),
                rs.getString("status"),
                responseStatus,
                rs.getString("response_payload"),
                toInstant(rs, "created_at"),
                toInstant(rs, "expires_at"));
    }
}
