package com.stockforge.inventory.adapter.out.persistence;

import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toInstant;
import static com.stockforge.inventory.adapter.out.persistence.SqlTypes.toTimestamp;

import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductRepository implements ProductRepository {

    private static final String COLUMNS = "id, sku, name, price_amount, currency, status, created_at, updated_at";

    private final JdbcClient jdbc;

    JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfSkuAvailable(Product product) {
        // ON CONFLICT makes "check SKU + insert" one atomic statement: no race between concurrent creators,
        // and no unique-violation error that would abort the surrounding transaction.
        int inserted = jdbc.sql("""
                        INSERT INTO products (id, sku, name, price_amount, currency, status, created_at, updated_at)
                        VALUES (:id, :sku, :name, :priceAmount, :currency, :status, :createdAt, :updatedAt)
                        ON CONFLICT (sku) DO NOTHING
                        """)
                .param("id", product.id().value())
                .param("sku", product.sku().value())
                .param("name", product.name())
                .param("priceAmount", product.price().amount())
                .param("currency", product.price().currency().getCurrencyCode())
                .param("status", product.status().name())
                .param("createdAt", toTimestamp(product.createdAt()))
                .param("updatedAt", toTimestamp(product.updatedAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM products WHERE id = :id")
                .param("id", id.value())
                .query(JdbcProductRepository::mapRow)
                .optional();
    }

    @Override
    public List<Product> findFirst(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM products ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query(JdbcProductRepository::mapRow)
                .list();
    }

    @Override
    public List<Product> findAfter(ProductId after, int limit) {
        // Keyset pagination: an index range scan on the primary key, constant cost regardless of page depth.
        return jdbc.sql("SELECT " + COLUMNS + " FROM products WHERE id > :after ORDER BY id LIMIT :limit")
                .param("after", after.value())
                .param("limit", limit)
                .query(JdbcProductRepository::mapRow)
                .list();
    }

    private static Product mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Product(
                ProductId.of(rs.getObject("id", UUID.class)),
                Sku.of(rs.getString("sku")),
                rs.getString("name"),
                new Money(rs.getBigDecimal("price_amount"), Currency.getInstance(rs.getString("currency"))),
                ProductStatus.valueOf(rs.getString("status")),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }
}
