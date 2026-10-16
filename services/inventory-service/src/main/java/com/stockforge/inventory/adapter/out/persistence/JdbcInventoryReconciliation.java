package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.domain.port.InventoryReconciliation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The same checks as {@code scripts/check-invariants.sql}, run continuously by the reconciliation job (spec §21). */
@Repository
class JdbcInventoryReconciliation implements InventoryReconciliation {

    private final JdbcClient jdbc;

    JdbcInventoryReconciliation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Discrepancy> stockConservationViolations() {
        return jdbc.sql("""
                        SELECT product_id, 'available+reserved+sold vs total' AS subject,
                               total_quantity AS expected,
                               available_quantity + reserved_quantity + sold_quantity AS actual
                        FROM inventory
                        WHERE available_quantity + reserved_quantity + sold_quantity <> total_quantity
                           OR available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0
                        """).query(JdbcInventoryReconciliation::discrepancy).list();
    }

    @Override
    public List<Discrepancy> reservedQuantityMismatches() {
        return jdbc.sql("""
                        SELECT i.product_id, 'reserved_quantity vs PENDING reservations' AS subject,
                               COALESCE(p.pending, 0) AS expected, i.reserved_quantity AS actual
                        FROM inventory i
                        LEFT JOIN (SELECT product_id, SUM(quantity) AS pending
                                   FROM reservations WHERE status = 'PENDING'
                                   GROUP BY product_id) p ON p.product_id = i.product_id
                        WHERE i.reserved_quantity <> COALESCE(p.pending, 0)
                        """).query(JdbcInventoryReconciliation::discrepancy).list();
    }

    @Override
    public List<Discrepancy> purchaseLimitCounterMismatches() {
        return jdbc.sql("""
                        SELECT l.product_id, 'user ' || l.user_id || ' active_quantity' AS subject,
                               COALESCE(r.held, 0) AS expected, l.active_quantity AS actual
                        FROM user_product_limits l
                        LEFT JOIN (SELECT user_id, product_id, SUM(quantity) AS held
                                   FROM reservations WHERE status IN ('PENDING', 'CONFIRMED')
                                   GROUP BY user_id, product_id) r
                               ON r.user_id = l.user_id AND r.product_id = l.product_id
                        WHERE l.active_quantity <> COALESCE(r.held, 0)
                        """).query(JdbcInventoryReconciliation::discrepancy).list();
    }

    private static Discrepancy discrepancy(ResultSet rs, int rowNum) throws SQLException {
        return new Discrepancy(
                rs.getObject("product_id", UUID.class),
                rs.getString("subject"),
                rs.getLong("expected"),
                rs.getLong("actual"));
    }
}
