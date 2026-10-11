package com.stockforge.payment.adapter.out.persistence;

import static com.stockforge.payment.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.payment.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.domain.model.UserId;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentRepository implements PaymentRepository {

    private final JdbcClient jdbc;

    JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Payment payment) {
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, user_id, amount, currency, status, simulation_outcome, created_at, updated_at)
                        VALUES (:id, :orderId, :userId, :amount, :currency, :status, :simulationOutcome, :createdAt, :updatedAt)
                        """)
                .param("id", payment.id().value())
                .param("orderId", payment.orderId().value())
                .param("userId", payment.userId().value())
                .param("amount", payment.amount())
                .param("currency", payment.currency())
                .param("status", payment.status().name())
                .param("simulationOutcome", payment.simulationOutcome())
                .param("createdAt", toTimestamp(payment.createdAt()))
                .param("updatedAt", toTimestamp(payment.updatedAt()))
                .update();
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        return jdbc.sql("""
                        SELECT id, order_id, user_id, amount, currency, status, simulation_outcome, created_at, updated_at
                        FROM payments
                        WHERE id = :id
                        """)
                .param("id", id.value())
                .query(JdbcPaymentRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
        return jdbc.sql("""
                        SELECT id, order_id, user_id, amount, currency, status, simulation_outcome, created_at, updated_at
                        FROM payments
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId.value())
                .query(JdbcPaymentRepository::mapRow)
                .optional();
    }

    @Override
    public void update(Payment payment) {
        jdbc.sql("""
                        UPDATE payments
                        SET status = :status, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("status", payment.status().name())
                .param("updatedAt", toTimestamp(payment.updatedAt()))
                .param("id", payment.id().value())
                .update();
    }

    private static Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                PaymentId.of(rs.getObject("id", UUID.class)),
                OrderId.of(rs.getObject("order_id", UUID.class)),
                UserId.of(rs.getObject("user_id", UUID.class)),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("simulation_outcome"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }
}
