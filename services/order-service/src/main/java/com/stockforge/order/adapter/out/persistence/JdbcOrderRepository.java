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
import com.stockforge.order.domain.model.SagaStep;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.OrderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderRepository implements OrderRepository {

    private static final String ORDER_COLUMNS =
            "id, user_id, reservation_id, status, saga_step, payment_id, total_amount, currency, created_at, updated_at";

    private final JdbcClient jdbc;

    JdbcOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfReservationUnordered(Order order) {
        // ON CONFLICT turns "check the reservation is unused + insert" into one atomic statement, and a conflict does
        // not abort the surrounding transaction (unlike catching a unique violation).
        int inserted = jdbc.sql("""
                        INSERT INTO orders (id, user_id, reservation_id, status, saga_step, payment_id, total_amount,
                                            currency, created_at, updated_at)
                        VALUES (:id, :userId, :reservationId, :status, :sagaStep, NULL, :total, :currency,
                                :createdAt, :updatedAt)
                        ON CONFLICT (reservation_id) DO NOTHING
                        """)
                .param("id", order.id().value())
                .param("userId", order.userId().value())
                .param("reservationId", order.reservationId().value())
                .param("status", order.status().name())
                .param("sagaStep", order.sagaStep().name())
                .param("total", order.totalAmount().amount())
                .param("currency", order.totalAmount().currency().getCurrencyCode())
                .param("createdAt", toTimestamp(order.createdAt()))
                .param("updatedAt", toTimestamp(order.updatedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
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
        order.history().forEach(entry -> insertHistory(order.id(), entry));
        return true;
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = :id")
                .param("id", id.value())
                .query(JdbcOrderRepository::mapOrderRow)
                .optional()
                .map(this::complete);
    }

    @Override
    public boolean saveTransition(Order before, Order after) {
        int updated = jdbc.sql("""
                        UPDATE orders
                        SET status = :status, saga_step = :sagaStep, payment_id = :paymentId, updated_at = :updatedAt
                        WHERE id = :id AND status = :expectedStatus AND saga_step = :expectedStep
                        """)
                .param("id", before.id().value())
                .param("status", after.status().name())
                .param("sagaStep", after.sagaStep().name())
                .param("paymentId", after.paymentId().orElse(null))
                .param("updatedAt", toTimestamp(after.updatedAt()))
                .param("expectedStatus", before.status().name())
                .param("expectedStep", before.sagaStep().name())
                .update();
        if (updated == 0) {
            return false;
        }
        after.history()
                .subList(before.history().size(), after.history().size())
                .forEach(entry -> insertHistory(after.id(), entry));
        return true;
    }

    @Override
    public List<Order> findByUser(UserId userId, Optional<PageKey> after, int limit) {
        // Keyset pagination on the (user_id, created_at DESC, id) index: constant cost regardless of page depth.
        List<OrderRow> rows = after.isEmpty()
                ? jdbc.sql("SELECT " + ORDER_COLUMNS + """
                                 FROM orders WHERE user_id = :userId
                                ORDER BY created_at DESC, id
                                LIMIT :limit
                                """)
                        .param("userId", userId.value())
                        .param("limit", limit)
                        .query(JdbcOrderRepository::mapOrderRow)
                        .list()
                : jdbc.sql("SELECT " + ORDER_COLUMNS + """
                                 FROM orders
                                WHERE user_id = :userId
                                  AND (created_at < :createdAt OR (created_at = :createdAt AND id > :id))
                                ORDER BY created_at DESC, id
                                LIMIT :limit
                                """)
                        .param("userId", userId.value())
                        .param("createdAt", toTimestamp(after.get().createdAt()))
                        .param("id", after.get().orderId())
                        .param("limit", limit)
                        .query(JdbcOrderRepository::mapOrderRow)
                        .list();
        return completeAll(rows);
    }

    @Override
    public List<Order> claimOrdersAwaitingPaymentLongerThan(Duration timeout, int limit) {
        // Database time decides, so instances with skewed clocks agree on which orders timed out.
        List<OrderRow> rows = jdbc.sql("SELECT " + ORDER_COLUMNS + """
                         FROM orders
                        WHERE saga_step = 'PAYMENT_REQUESTED'
                          AND created_at < now() - make_interval(secs => :timeoutSeconds)
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("timeoutSeconds", timeout.toMillis() / 1000.0)
                .param("limit", limit)
                .query(JdbcOrderRepository::mapOrderRow)
                .list();
        return completeAll(rows);
    }

    private void insertHistory(OrderId orderId, OrderStatusHistoryEntry entry) {
        jdbc.sql("""
                        INSERT INTO order_status_history (order_id, from_status, to_status, reason, occurred_at)
                        VALUES (:orderId, :fromStatus, :toStatus, :reason, :occurredAt)
                        """)
                .param("orderId", orderId.value())
                .param(
                        "fromStatus",
                        entry.fromStatus() == null ? null : entry.fromStatus().name())
                .param("toStatus", entry.toStatus().name())
                .param("reason", entry.reason())
                .param("occurredAt", toTimestamp(entry.occurredAt()))
                .update();
    }

    private Order complete(OrderRow row) {
        return completeAll(List.of(row)).getFirst();
    }

    /** Loads items and history of all rows with two queries instead of two per order. */
    private List<Order> completeAll(List<OrderRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(OrderRow::id).toList();
        Map<UUID, List<OrderItem>> items = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT order_id, product_id, quantity, unit_price, currency
                        FROM order_items WHERE order_id IN (:ids)
                        ORDER BY order_id, product_id
                        """).param("ids", ids).query(rs -> {
            items.computeIfAbsent(rs.getObject("order_id", UUID.class), id -> new ArrayList<>())
                    .add(new OrderItem(
                            ProductId.of(rs.getObject("product_id", UUID.class)),
                            rs.getInt("quantity"),
                            new Money(rs.getBigDecimal("unit_price"), Currency.getInstance(rs.getString("currency")))));
        });
        Map<UUID, List<OrderStatusHistoryEntry>> history = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT order_id, from_status, to_status, reason, occurred_at
                        FROM order_status_history WHERE order_id IN (:ids)
                        ORDER BY order_id, id
                        """).param("ids", ids).query(rs -> {
            String from = rs.getString("from_status");
            history.computeIfAbsent(rs.getObject("order_id", UUID.class), id -> new ArrayList<>())
                    .add(new OrderStatusHistoryEntry(
                            from == null ? null : OrderStatus.valueOf(from),
                            OrderStatus.valueOf(rs.getString("to_status")),
                            rs.getString("reason"),
                            toInstant(rs, "occurred_at")));
        });
        return rows.stream()
                .map(row -> new Order(
                        OrderId.of(row.id()),
                        UserId.of(row.userId()),
                        ReservationId.of(row.reservationId()),
                        row.status(),
                        row.sagaStep(),
                        Optional.ofNullable(row.paymentId()),
                        row.total(),
                        items.getOrDefault(row.id(), List.of()),
                        history.getOrDefault(row.id(), List.of()),
                        row.createdAt(),
                        row.updatedAt()))
                .toList();
    }

    private static OrderRow mapOrderRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("reservation_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")),
                SagaStep.valueOf(rs.getString("saga_step")),
                rs.getObject("payment_id", UUID.class),
                new Money(rs.getBigDecimal("total_amount"), Currency.getInstance(rs.getString("currency"))),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private record OrderRow(
            UUID id,
            UUID userId,
            UUID reservationId,
            OrderStatus status,
            SagaStep sagaStep,
            UUID paymentId,
            Money total,
            Instant createdAt,
            Instant updatedAt) {}
}
