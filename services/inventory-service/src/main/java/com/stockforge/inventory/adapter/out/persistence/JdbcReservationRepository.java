package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.ReservationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReservationRepository implements ReservationRepository {

    private final JdbcClient jdbc;

    JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Reservation reservation) {
        jdbc.sql("""
                        INSERT INTO reservations (id, user_id, product_id, quantity, status, expires_at, created_at, updated_at)
                        VALUES (:id, :userId, :productId, :quantity, :status, :expiresAt, :createdAt, :updatedAt)
                        """)
                .param("id", reservation.id().value())
                .param("userId", reservation.userId().value())
                .param("productId", reservation.productId().value())
                .param("quantity", reservation.quantity())
                .param("status", reservation.status().name())
                .param("expiresAt", toTimestamp(reservation.expiresAt()))
                .param("createdAt", toTimestamp(reservation.createdAt()))
                .param("updatedAt", toTimestamp(reservation.updatedAt()))
                .update();
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
        return jdbc.sql("""
                        SELECT id, user_id, product_id, quantity, status, expires_at, created_at, updated_at
                        FROM reservations
                        WHERE id = :id
                        """)
                .param("id", id.value())
                .query(JdbcReservationRepository::mapRow)
                .optional();
    }

    @Override
    public boolean updateStatusIfCurrent(
            ReservationId id, ReservationStatus currentStatus, ReservationStatus newStatus, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE reservations
                        SET status = :newStatus, updated_at = :updatedAt
                        WHERE id = :id AND status = :currentStatus
                        """)
                .param("id", id.value())
                .param("currentStatus", currentStatus.name())
                .param("newStatus", newStatus.name())
                .param("updatedAt", toTimestamp(now))
                .update();
        return rows == 1;
    }

    @Override
    public int findActiveQuantityByUserAndProduct(UserId userId, ProductId productId) {
        Integer total = jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0)
                        FROM reservations
                        WHERE user_id = :userId
                          AND product_id = :productId
                          AND status IN ('PENDING', 'CONFIRMED')
                        """)
                .param("userId", userId.value())
                .param("productId", productId.value())
                .query(Integer.class)
                .single();
        return total != null ? total : 0;
    }

    @Override
    public List<Reservation> findExpiredPending(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, product_id, quantity, status, expires_at, created_at, updated_at
                        FROM reservations
                        WHERE status = 'PENDING' AND expires_at < :now
                        ORDER BY expires_at
                        LIMIT :limit
                        """)
                .param("now", toTimestamp(now))
                .param("limit", limit)
                .query(JdbcReservationRepository::mapRow)
                .list();
    }

    private static Reservation mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation(
                ReservationId.of(rs.getObject("id", UUID.class)),
                UserId.of(rs.getObject("user_id", UUID.class)),
                ProductId.of(rs.getObject("product_id", UUID.class)),
                rs.getInt("quantity"),
                ReservationStatus.valueOf(rs.getString("status")),
                toInstant(rs, "expires_at"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }
}
