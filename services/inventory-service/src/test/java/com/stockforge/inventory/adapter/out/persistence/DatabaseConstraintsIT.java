package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves the database enforces the domain invariants on its own, by writing SQL directly and bypassing the domain
 * model. If application code ever has a bug, these constraints are the last line of defense.
 */
@InventoryIntegrationTest
class DatabaseConstraintsIT {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void stockMustBeConservedAcrossBuckets() {
        UUID productId = insertProduct(uniqueSku());

        assertThatThrownBy(() -> insertInventory(productId, 10, 5, 3, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_stock_conservation");
    }

    @Test
    void noStockBucketMayBeNegativeEvenIfTheTotalsAddUp() {
        UUID productId = insertProduct(uniqueSku());

        assertThatThrownBy(() -> insertInventory(productId, 10, -1, 6, 5))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_available_non_negative");
    }

    @Test
    void updatesThatWouldOversellAreRejected() {
        UUID productId = insertProduct(uniqueSku());
        insertInventory(productId, 1, 1, 0, 0);

        // The classic oversell: decrementing without checking. The database refuses to go below zero.
        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE inventory
                                SET available_quantity = available_quantity - 2, sold_quantity = sold_quantity + 2,
                                    total_quantity = total_quantity
                                WHERE product_id = :id
                                """).param("id", productId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_");
    }

    @Test
    void stockCannotExistWithoutItsProduct() {
        assertThatThrownBy(() -> insertInventory(UUID.randomUUID(), 1, 1, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_product_id_fkey");
    }

    @Test
    void skuFormatAndUniquenessAreEnforced() {
        assertThatThrownBy(() -> insertProduct("lower-case"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("products_sku_format");

        String sku = uniqueSku();
        insertProduct(sku);
        assertThatThrownBy(() -> insertProduct(sku))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("products_sku_unique");
    }

    @Test
    void productStatusMustBeKnown() {
        assertThatThrownBy(() -> insertProduct(uniqueSku(), "DELETED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("products_status_valid");
    }

    private UUID insertProduct(String sku) {
        return insertProduct(sku, "ACTIVE");
    }

    private UUID insertProduct(String sku, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, 'Constraint test', 1.00, 'USD', :status, :now, :now)
                        """)
                .param("id", id)
                .param("sku", sku)
                .param("status", status)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    private void insertInventory(UUID productId, int total, int available, int reserved, int sold) {
        jdbc.sql("""
                        INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity,
                                               sold_quantity, updated_at)
                        VALUES (:productId, :total, :available, :reserved, :sold, now())
                        """)
                .param("productId", productId)
                .param("total", total)
                .param("available", available)
                .param("reserved", reserved)
                .param("sold", sold)
                .update();
    }
}
