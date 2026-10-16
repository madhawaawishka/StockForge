package com.stockforge.payment.adapter.out.persistence;

import static com.stockforge.payment.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.payment.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.payment.domain.model.Money;
import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.model.PaymentStatus;
import com.stockforge.payment.domain.model.UserId;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentRepository implements PaymentRepository {

    private static final String COLUMNS = "id, order_id, user_id, amount, currency, status, created_at, updated_at";

    private final JdbcClient jdbc;

    JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Payment insertOrGetForOrder(Payment payment) {
        // The unique order_id makes "one payment per order" a database guarantee; a redelivered request lands here.
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, user_id, amount, currency, status, created_at, updated_at)
                        VALUES (:id, :orderId, :userId, :amount, :currency, :status, :createdAt, :updatedAt)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", payment.id().value())
                .param("orderId", payment.orderId().value())
                .param("userId", payment.userId().value())
                .param("amount", payment.amount().amount())
                .param("currency", payment.amount().currency().getCurrencyCode())
                .param("status", payment.status().name())
                .param("createdAt", toTimestamp(payment.createdAt()))
                .param("updatedAt", toTimestamp(payment.updatedAt()))
                .update();
        return findByOrderId(payment.orderId()).orElseThrow();
    }

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE order_id = :orderId")
                .param("orderId", orderId.value())
                .query(JdbcPaymentRepository::mapRow)
                .optional();
    }

    @Override
    public boolean saveTransition(Payment before, Payment after) {
        return jdbc.sql("""
                        UPDATE payments SET status = :status, updated_at = :updatedAt
                        WHERE id = :id AND status = :expected
                        """)
                        .param("id", before.id().value())
                        .param("status", after.status().name())
                        .param("updatedAt", toTimestamp(after.updatedAt()))
                        .param("expected", before.status().name())
                        .update()
                == 1;
    }

    private static Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                PaymentId.of(rs.getObject("id", UUID.class)),
                OrderId.of(rs.getObject("order_id", UUID.class)),
                UserId.of(rs.getObject("user_id", UUID.class)),
                new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                PaymentStatus.valueOf(rs.getString("status")),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }
}
