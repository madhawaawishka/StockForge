package com.stockforge.order.adapter.out.persistence;

import static com.stockforge.order.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
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
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (key, user_id, request_hash, status, created_at, expires_at)
                        VALUES (:key, :userId, :requestHash, 'IN_PROGRESS', :createdAt, :expiresAt)
                        ON CONFLICT (user_id, key) DO NOTHING
                        """)
                        .param("key", key)
                        .param("userId", userId.value())
                        .param("requestHash", requestHash)
                        .param("createdAt", toTimestamp(now))
                        .param("expiresAt", toTimestamp(expiresAt))
                        .update()
                == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key, UserId userId) {
        return jdbc.sql("""
                        SELECT key, user_id, request_hash, response_status, response_payload
                        FROM idempotency_keys
                        WHERE user_id = :userId AND key = :key
                        """)
                .param("userId", userId.value())
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("key"),
                        UserId.of(rs.getObject("user_id", UUID.class)),
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_payload")))
                .optional();
    }

    @Override
    public void complete(String key, UserId userId, int responseStatus, String response) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = :responseStatus, response_payload = :response
                        WHERE user_id = :userId AND key = :key
                        """)
                .param("userId", userId.value())
                .param("key", key)
                .param("responseStatus", responseStatus)
                .param("response", response)
                .update();
    }

    @Override
    public int deleteExpired(Instant now, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM idempotency_keys
                        WHERE (user_id, key) IN (SELECT user_id, key FROM idempotency_keys
                                                 WHERE expires_at < :now LIMIT :batch)
                        """)
                .param("now", toTimestamp(now))
                .param("batch", batchSize)
                .update();
    }
}
