package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.port.AuditLog;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Adds a product with its initial stock, atomically and audited (FR-1, FR-10). */
public class CreateProductUseCase {

    private final ProductRepository products;
    private final InventoryRepository inventories;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateProductUseCase(
            ProductRepository products,
            InventoryRepository inventories,
            AuditLog auditLog,
            IdGenerator ids,
            Clock clock) {
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        auditLog.record(
                command.actorId(),
                "PRODUCT_CREATED",
                "Product",
                product.id().toString(),
                Map.of(
                        "sku", product.sku().value(),
                        "price", product.price().toString(),
                        "initialStock", command.initialStock()));
        return new CreateProductResult.Created(product, inventory);
    }
}
