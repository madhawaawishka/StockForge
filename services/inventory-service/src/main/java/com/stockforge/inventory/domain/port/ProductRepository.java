package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import java.util.List;
import java.util.Optional;

/** Persistence port for catalog products. */
public interface ProductRepository {

    /**
     * Inserts the product unless another product already uses its SKU.
     *
     * <p>The check and the insert are a single atomic operation, so concurrent creations of the same SKU cannot both
     * succeed, and a conflict leaves the surrounding transaction usable.
     *
     * @return {@code true} if the product was inserted, {@code false} if the SKU is taken
     */
    boolean insertIfSkuAvailable(Product product);

    Optional<Product> findById(ProductId id);

    /** Returns the first {@code limit} products in id order (keyset pagination, first page). */
    List<Product> findFirst(int limit);

    /** Returns up to {@code limit} products whose id is greater than {@code after}, in id order. */
    List<Product> findAfter(ProductId after, int limit);
}
