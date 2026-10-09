package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fake {@link ProductRepository} with the same ordering and uniqueness semantics as the PostgreSQL adapter. */
public class InMemoryProductRepository implements ProductRepository {

    /** PostgreSQL orders UUIDs as unsigned bytes; {@link UUID#compareTo} is signed, so it cannot be used. */
    public static final Comparator<ProductId> POSTGRES_UUID_ORDER = Comparator.comparing(
                    (ProductId id) -> id.value().getMostSignificantBits(), Long::compareUnsigned)
            .thenComparing(id -> id.value().getLeastSignificantBits(), Long::compareUnsigned);

    private final List<Product> products = new ArrayList<>();

    @Override
    public synchronized boolean insertIfSkuAvailable(Product product) {
        boolean skuTaken = products.stream().anyMatch(existing -> existing.sku().equals(product.sku()));
        if (skuTaken) {
            return false;
        }
        products.add(product);
        return true;
    }

    @Override
    public synchronized Optional<Product> findById(ProductId id) {
        return products.stream().filter(product -> product.id().equals(id)).findFirst();
    }

    @Override
    public synchronized List<Product> findFirst(int limit) {
        return products.stream()
                .sorted(Comparator.comparing(Product::id, POSTGRES_UUID_ORDER))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Product> findAfter(ProductId after, int limit) {
        return products.stream()
                .filter(product -> POSTGRES_UUID_ORDER.compare(product.id(), after) > 0)
                .sorted(Comparator.comparing(Product::id, POSTGRES_UUID_ORDER))
                .limit(limit)
                .toList();
    }

    public synchronized int count() {
        return products.size();
    }
}
