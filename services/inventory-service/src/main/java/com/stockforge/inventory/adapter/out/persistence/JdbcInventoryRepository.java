package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    @Override
    public Optional<Inventory> findByProductIdForUpdate(ProductId productId) {
        return jdbc.sql("""
                        SELECT product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity,
                               version, updated_at
                        FROM inventory
                        WHERE product_id = :productId
                        FOR UPDATE
                        """)
                .param("productId", productId.value())
                .query(JdbcInventoryRepository::mapRow)
                .optional();
    }

    @Override
    public boolean decrementAvailableAndIncrementReserved(ProductId productId, int quantity, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE inventory
                        SET available_quantity = available_quantity - :qty,
                            reserved_quantity  = reserved_quantity  + :qty,
                            version            = version + 1,
                            updated_at         = :updatedAt
                        WHERE product_id = :productId
                          AND available_quantity >= :qty
                        """)
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("updatedAt", toTimestamp(now))
                .update();
        return rows == 1;
    }

    @Override
    public boolean updateVersionedStock(ProductId productId, int quantity, long expectedVersion, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE inventory
                        SET available_quantity = available_quantity - :qty,
                            reserved_quantity  = reserved_quantity  + :qty,
                            version            = version + 1,
                            updated_at         = :updatedAt
                        WHERE product_id = :productId
                          AND version = :version
                          AND available_quantity >= :qty
                        """)
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("version", expectedVersion)
                .param("updatedAt", toTimestamp(now))
                .update();
        return rows == 1;
    }

    @Override
    public void updateUnconditional(ProductId productId, int newAvailable, int newReserved, Instant now) {
        jdbc.sql("""
                        UPDATE inventory
                        SET available_quantity = :available,
                            reserved_quantity  = :reserved,
                            updated_at         = :updatedAt
                        WHERE product_id = :productId
                        """)
                .param("productId", productId.value())
                .param("available", newAvailable)
                .param("reserved", newReserved)
                .param("updatedAt", toTimestamp(now))
                .update();
    }

    @Override
    public boolean releaseReservedStock(ProductId productId, int quantity, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE inventory
                        SET available_quantity = available_quantity + :qty,
                            reserved_quantity  = reserved_quantity  - :qty,
                            version            = version + 1,
                            updated_at         = :updatedAt
                        WHERE product_id = :productId
                          AND reserved_quantity >= :qty
                        """)
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("updatedAt", toTimestamp(now))
                .update();
        return rows == 1;
    }

    @Override
    public boolean confirmReservedStock(ProductId productId, int quantity, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE inventory
                        SET reserved_quantity = reserved_quantity - :qty,
                            sold_quantity     = sold_quantity     + :qty,
                            version           = version + 1,
                            updated_at        = :updatedAt
                        WHERE product_id = :productId
                          AND reserved_quantity >= :qty
                        """)
                .param("productId", productId.value())
                .param("qty", quantity)
                .param("updatedAt", toTimestamp(now))
                .update();
        return rows == 1;
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
