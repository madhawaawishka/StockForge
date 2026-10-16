package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserPurchaseLimitRepository implements UserPurchaseLimitRepository {

    private final JdbcClient jdbc;

    JdbcUserPurchaseLimitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(UserId userId, ProductId productId, int quantity, int maxLimit, Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO user_product_limits (user_id, product_id, active_quantity, limit_quantity, updated_at)
                        SELECT :userId, :productId, :qty, :maxLimit, :updatedAt
                        WHERE :qty <= :maxLimit
                        ON CONFLICT (user_id, product_id) DO UPDATE
                        SET active_quantity = user_product_limits.active_quantity + :qty,
                            limit_quantity  = :maxLimit,
                            updated_at      = :updatedAt
                        WHERE user_product_limits.active_quantity + :qty <= :maxLimit
                        """)
                .param("userId", userId.value())
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("updatedAt", toTimestamp(now))
                .param("maxLimit", maxLimit)
                .update();
        return rows == 1;
    }

    @Override
    public void release(UserId userId, ProductId productId, int quantity, Instant now) {
        jdbc.sql("""
                        UPDATE user_product_limits
                        SET active_quantity = active_quantity - :qty,
                            updated_at = :updatedAt
                        WHERE user_id = :userId AND product_id = :productId
                        """)
                .param("userId", userId.value())
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("updatedAt", toTimestamp(now))
                .update();
        // No GREATEST(0, ...) clamp: releasing more than was acquired is a bug, and the CHECK constraint
        // (active_quantity >= 0) makes it fail loudly instead of hiding it.
    }
}
