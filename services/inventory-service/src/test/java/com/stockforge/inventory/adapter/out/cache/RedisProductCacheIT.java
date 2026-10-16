package com.stockforge.inventory.adapter.out.cache;

import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.port.ProductCache;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cache's invalidation contract against a real Redis (spec §17): inside a transaction a cached entry is deleted
 * only once the transaction commits. Deleting earlier would let a concurrent reader re-cache the old value before the
 * commit; deleting on rollback would throw away a still-valid entry.
 */
@InventoryIntegrationTest
class RedisProductCacheIT {

    @Autowired
    private ProductCache cache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Product product;

    @BeforeEach
    void cacheAProduct() {
        transaction = new TransactionTemplate(transactionManager);
        product = Product.create(
                ProductId.of(UUID.randomUUID()),
                Sku.of(uniqueSku()),
                "Cached product",
                Money.of("19.99", "USD"),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        cache.putProduct(product);
        assertThat(cache.getProduct(product.id())).contains(product);
    }

    @Test
    void anEvictionInsideATransactionWaitsForTheCommit() {
        transaction.executeWithoutResult(status -> {
            cache.evictProduct(product.id());
            assertThat(cache.getProduct(product.id()))
                    .as("still cached until the transaction commits")
                    .isPresent();
        });

        assertThat(cache.getProduct(product.id())).isEmpty();
    }

    @Test
    void aRolledBackTransactionLeavesTheCacheAlone() {
        transaction.executeWithoutResult(status -> {
            cache.evictProduct(product.id());
            status.setRollbackOnly();
        });

        assertThat(cache.getProduct(product.id())).contains(product);
    }

    @Test
    void outsideATransactionTheEntryIsDeletedAtOnce() {
        cache.evictProduct(product.id());

        assertThat(cache.getProduct(product.id())).isEmpty();
    }
}
