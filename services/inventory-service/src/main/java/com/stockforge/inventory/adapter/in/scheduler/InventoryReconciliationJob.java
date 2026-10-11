package com.stockforge.inventory.adapter.in.scheduler;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic reconciliation job verifying stock conservation invariants and reservation sum integrity (spec §21). */
@Component
public class InventoryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationJob.class);

    private final JdbcClient jdbc;

    public InventoryReconciliationJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${stockforge.reconciliation.interval-ms:60000}")
    public void reconcileInventory() {
        // 1. Verify stock conservation invariant: available + reserved + sold == total
        List<Map<String, Object>> conservationViolations = jdbc.sql("""
                        SELECT product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity
                        FROM inventory
                        WHERE available_quantity + reserved_quantity + sold_quantity != total_quantity
                        """).query().listOfRows();

        if (!conservationViolations.isEmpty()) {
            log.error(
                    "CRITICAL: Stock conservation invariant violated for {} products: {}",
                    conservationViolations.size(),
                    conservationViolations);
        }

        // 2. Verify reserved_quantity equals sum of active PENDING reservations
        List<Map<String, Object>> reservationMismatches = jdbc.sql("""
                        WITH pending_sums AS (
                            SELECT product_id, COALESCE(SUM(quantity), 0) AS actual_pending
                            FROM reservations
                            WHERE status = 'PENDING'
                            GROUP BY product_id
                        )
                        SELECT i.product_id, i.reserved_quantity, COALESCE(ps.actual_pending, 0) AS actual_pending
                        FROM inventory i
                        LEFT JOIN pending_sums ps ON i.product_id = ps.product_id
                        WHERE i.reserved_quantity != COALESCE(ps.actual_pending, 0)
                        """).query().listOfRows();

        if (!reservationMismatches.isEmpty()) {
            log.error(
                    "CRITICAL: Reservation sum mismatch detected for {} products: {}",
                    reservationMismatches.size(),
                    reservationMismatches);
        } else if (conservationViolations.isEmpty()) {
            log.debug("Inventory reconciliation verified: all stock invariants intact.");
        }
    }
}
