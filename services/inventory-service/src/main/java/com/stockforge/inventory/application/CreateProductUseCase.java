package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Transactional;

/** Adds a product to the catalog with its initial stock, atomically. */
public class CreateProductUseCase {

    private final ProductRepository products;
    private final InventoryRepository inventories;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateProductUseCase(
            ProductRepository products, InventoryRepository inventories, IdGenerator ids, Clock clock) {
        this.products = products;
        this.inventories = inventories;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public CreateProductResult execute(CreateProductCommand command) {
        // PostgreSQL stores microseconds; truncating here keeps returned values identical to stored ones.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Build both aggregates first so every validation error surfaces before any write.
        Product product =
                Product.create(ProductId.of(ids.newId()), command.sku(), command.name(), command.price(), now);
        Inventory inventory = Inventory.initial(product.id(), command.initialStock(), now);

        if (!products.insertIfSkuAvailable(product)) {
            return new CreateProductResult.SkuAlreadyExists(command.sku());
        }
        inventories.insert(inventory);
        return new CreateProductResult.Created(product, inventory);
    }
}
