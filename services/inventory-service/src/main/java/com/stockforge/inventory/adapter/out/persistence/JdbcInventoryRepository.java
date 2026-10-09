package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInventoryRepository implements InventoryRepository {

    private final JdbcClient jdbc;

    JdbcInventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Inventory inventory) {
        jdbc.sql("""
                        INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity,
                                               sold_quantity, version, updated_at)
                        VALUES (:productId, :total, :available, :reserved, :sold, :version, :updatedAt)
                        """)
                .param("productId", inventory.productId().value())
                .param("total", inventory.totalQuantity())
                .param("available", inventory.availableQuantity())
                .param("reserved", inventory.reservedQuantity())
                .param("sold", inventory.soldQuantity())
                .param("version", inventory.version())
                .param("updatedAt", toTimestamp(inventory.updatedAt()))
                .update();
    }

    @Override
    public Optional<Inventory> findByProductId(ProductId productId) {
        return jdbc.sql("""
                        SELECT product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity,
                               version, updated_at
                        FROM inventory
                        WHERE product_id = :productId
                        """)
                .param("productId", productId.value())
                .query(JdbcInventoryRepository::mapRow)
                .optional();
    }

    private static Inventory mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Inventory(
                ProductId.of(rs.getObject("product_id", UUID.class)),
                rs.getInt("total_quantity"),
                rs.getInt("available_quantity"),
                rs.getInt("reserved_quantity"),
                rs.getInt("sold_quantity"),
                rs.getLong("version"),
                toInstant(rs, "updated_at"));
    }
}
