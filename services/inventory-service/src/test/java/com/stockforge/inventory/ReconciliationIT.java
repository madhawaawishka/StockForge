package com.stockforge.inventory;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.application.ReconcileInventoryUseCase;
import com.stockforge.inventory.domain.port.InventoryReconciliation.Discrepancy;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The reconciliation job detects corruption that the CHECK constraints cannot see (spec §21): here, a PENDING
 * reservation whose units were never moved into the reserved bucket, as a buggy code path or a manual edit would leave
 * it.
 */
@InventoryIntegrationTest
class ReconciliationIT {

    @Autowired
    private ReconcileInventoryUseCase reconcile;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void reportsAReservationThatIsNotBackedByReservedStock() {
        UUID product = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, 'Corrupted', 1.00, 'USD', 'ACTIVE', :now, :now)
                        """)
                .param("id", product)
                .param("sku", uniqueSku())
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity,
                                               sold_quantity, version, updated_at)
                        VALUES (:id, 5, 5, 0, 0, 0, :now)
                        """).param("id", product).param("now", now).update();
        // Valid on its own, so every constraint accepts it; inconsistent with the inventory row.
        jdbc.sql("""
                        INSERT INTO reservations (id, user_id, product_id, quantity, status, expires_at, created_at,
                                                  updated_at)
                        VALUES (:id, :user, :product, 2, 'PENDING', :expires, :now, :now)
                        """)
                .param("id", reservation)
                .param("user", UUID.randomUUID())
                .param("product", product)
                .param("expires", now.plusMinutes(10))
                .param("now", now)
                .update();
        try {
            ReconcileInventoryUseCase.Report report = reconcile.reconcile();

            assertThat(report.reservedMismatches())
                    .contains(new Discrepancy(product, "reserved_quantity vs PENDING reservations", 2, 0));
            assertThat(report.violations()).isPositive();
        } finally {
            jdbc.sql("DELETE FROM reservations WHERE id = :id")
                    .param("id", reservation)
                    .update();
        }
    }
}
