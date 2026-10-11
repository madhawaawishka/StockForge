package com.stockforge.order.adapter.out.persistence;

import static com.stockforge.order.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.order.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.OrderStatusHistoryEntry;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.OrderRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;

    JdbcOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Order order) {
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at)
                        VALUES (:id, :userId, :reservationId, :status, :totalAmount, :currency, :createdAt, :updatedAt)
                        """)
                .param("id", order.id().value())
                .param("userId", order.userId().value())
                .param("reservationId", order.reservationId().value())
                .param("status", order.status().name())
                .param("totalAmount", order.totalAmount().amount())
                .param("currency", order.totalAmount().currency().getCurrencyCode())
                .param("createdAt", toTimestamp(order.createdAt()))
                .param("updatedAt", toTimestamp(order.updatedAt()))
                .update();

        for (OrderItem item : order.items()) {
            jdbc.sql("""
                            INSERT INTO order_items (order_id, product_id, quantity, unit_price, currency)
                            VALUES (:orderId, :productId, :quantity, :unitPrice, :currency)
                            """)
                    .param("orderId", order.id().value())
                    .param("productId", item.productId().value())
                    .param("quantity", item.quantity())
                    .param("unitPrice", item.unitPrice().amount())
                    .param("currency", item.unitPrice().currency().getCurrencyCode())
                    .update();
        }

        for (OrderStatusHistoryEntry history : order.history()) {
            jdbc.sql("""
                            INSERT INTO order_status_history (order_id, from_status, to_status, reason, occurred_at)
                            VALUES (:orderId, :fromStatus, :toStatus, :reason, :occurredAt)
                            """)
                    .param("orderId", order.id().value())
                    .param(
                            "fromStatus",
                            history.fromStatus() != null ? history.fromStatus().name() : null)
                    .param("toStatus", history.toStatus().name())
                    .param("reason", history.reason())
                    .param("occurredAt", toTimestamp(history.occurredAt()))
                    .update();
        }
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        Optional<OrderSummary> summary = jdbc.sql("""
                        SELECT id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at
                        FROM orders
                        WHERE id = :id
                        """)
                .param("id", id.value())
                .query(JdbcOrderRepository::mapOrderSummary)
                .optional();

        return summary.map(this::populateOrder);
    }

    @Override
    public Optional<Order> findByReservationId(ReservationId reservationId) {
        Optional<OrderSummary> summary = jdbc.sql("""
                        SELECT id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at
                        FROM orders
                        WHERE reservation_id = :reservationId
                        """)
                .param("reservationId", reservationId.value())
                .query(JdbcOrderRepository::mapOrderSummary)
                .optional();

        return summary.map(this::populateOrder);
    }

    @Override
    public List<Order> findByUserId(UserId userId, int limit) {
        List<OrderSummary> summaries = jdbc.sql("""
                        SELECT id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at
                        FROM orders
                        WHERE user_id = :userId
                        ORDER BY created_at DESC, id
                        LIMIT :limit
                        """)
                .param("userId", userId.value())
                .param("limit", limit)
                .query(JdbcOrderRepository::mapOrderSummary)
                .list();

        return summaries.stream().map(this::populateOrder).toList();
    }

    @Override
    public boolean updateStatusIfCurrent(
            OrderId id, OrderStatus currentStatus, OrderStatus newStatus, String reason, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE orders
                        SET status = :newStatus, updated_at = :updatedAt
                        WHERE id = :id AND status = :currentStatus
                        """)
                .param("id", id.value())
                .param("currentStatus", currentStatus.name())
                .param("newStatus", newStatus.name())
                .param("updatedAt", toTimestamp(now))
                .update();

        if (rows == 1) {
            jdbc.sql("""
                            INSERT INTO order_status_history (order_id, from_status, to_status, reason, occurred_at)
                            VALUES (:orderId, :fromStatus, :toStatus, :reason, :occurredAt)
                            """)
                    .param("orderId", id.value())
                    .param("fromStatus", currentStatus.name())
                    .param("toStatus", newStatus.name())
                    .param("reason", reason)
                    .param("occurredAt", toTimestamp(now))
                    .update();
            return true;
        }
        return false;
    }

    @Override
    public List<Order> findStuckPendingOrders(Instant createdBefore, int limit) {
        List<OrderSummary> summaries = jdbc.sql("""
                        SELECT id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at
                        FROM orders
                        WHERE status = 'PENDING_PAYMENT' AND created_at < :createdBefore
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("createdBefore", toTimestamp(createdBefore))
                .param("limit", limit)
                .query(JdbcOrderRepository::mapOrderSummary)
                .list();

        return summaries.stream().map(this::populateOrder).toList();
    }

    private Order populateOrder(OrderSummary s) {
        List<OrderItem> items = jdbc.sql("""
                        SELECT product_id, quantity, unit_price, currency
                        FROM order_items
                        WHERE order_id = :orderId
                        """)
                .param("orderId", s.id)
                .query(JdbcOrderRepository::mapOrderItem)
                .list();

        List<OrderStatusHistoryEntry> history = jdbc.sql("""
                        SELECT from_status, to_status, reason, occurred_at
                        FROM order_status_history
                        WHERE order_id = :orderId
                        ORDER BY id ASC
                        """)
                .param("orderId", s.id)
                .query(JdbcOrderRepository::mapOrderHistory)
                .list();

        return new Order(
                OrderId.of(s.id),
                UserId.of(s.userId),
                ReservationId.of(s.reservationId),
                s.status,
                s.totalAmount,
                items,
                history,
                s.createdAt,
                s.updatedAt);
    }

    private record OrderSummary(
            UUID id,
            UUID userId,
            UUID reservationId,
            OrderStatus status,
            Money totalAmount,
            Instant createdAt,
            Instant updatedAt) {}

    private static OrderSummary mapOrderSummary(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("total_amount");
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new OrderSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("reservation_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")),
                new Money(amount, currency),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private static OrderItem mapOrderItem(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal price = rs.getBigDecimal("unit_price");
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new OrderItem(
                ProductId.of(rs.getObject("product_id", UUID.class)),
                rs.getInt("quantity"),
                new Money(price, currency));
    }

    private static OrderStatusHistoryEntry mapOrderHistory(ResultSet rs, int rowNum) throws SQLException {
        String from = rs.getString("from_status");
        return new OrderStatusHistoryEntry(
                from != null ? OrderStatus.valueOf(from) : null,
                OrderStatus.valueOf(rs.getString("to_status")),
                rs.getString("reason"),
                toInstant(rs, "occurred_at"));
    }
}
