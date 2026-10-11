package com.stockforge.gateway.application;

import com.stockforge.gateway.domain.model.AvailabilityView;
import com.stockforge.gateway.domain.model.ProductView;
import com.stockforge.gateway.domain.port.InventoryClient;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProductGatewayQueries {

    private final InventoryClient inventoryClient;

    public ProductGatewayQueries(InventoryClient inventoryClient) {
        this.inventoryClient = Objects.requireNonNull(inventoryClient, "inventoryClient must not be null");
    }

    public Optional<ProductView> getProduct(String id) {
        return inventoryClient.getProduct(id);
    }

    public List<ProductView> listProducts(String cursor, int limit) {
        return inventoryClient.listProducts(cursor, limit);
    }

    public Optional<AvailabilityView> getAvailability(String productId) {
        return inventoryClient.getAvailability(productId);
    }
}
