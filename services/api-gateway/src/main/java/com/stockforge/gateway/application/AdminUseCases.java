package com.stockforge.gateway.application;

import com.stockforge.gateway.domain.model.Caller;
import com.stockforge.gateway.domain.model.ServiceProblem;
import com.stockforge.gateway.domain.model.Views;
import com.stockforge.gateway.domain.port.InventoryApi;
import com.stockforge.gateway.domain.port.OrderApi;
import java.util.Objects;

/**
 * Administrative operations (FR-10). The security filter chain already restricts {@code /api/v1/admin/**} to the ADMIN
 * role; the check is repeated here so a routing mistake cannot expose these operations. The owning services write the
 * audit log entries with the administrator's id.
 */
public class AdminUseCases {

    private final InventoryApi inventory;
    private final OrderApi orders;

    public AdminUseCases(InventoryApi inventory, OrderApi orders) {
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
    }

    public Views.Product createProduct(
            Caller caller, String sku, String name, String priceAmount, String priceCurrency, int initialStock) {
        requireAdmin(caller);
        return inventory.createProduct(sku, name, priceAmount, priceCurrency, initialStock, caller.userId());
    }

    public Views.Idempotent<Views.Availability> adjustStock(
            Caller caller, String idempotencyKey, String productId, int delta, String reason) {
        requireAdmin(caller);
        return inventory.adjustStock(idempotencyKey, productId, delta, reason, caller.userId());
    }

    public int replayDeadLetters(Caller caller, String topic, int maxMessages) {
        requireAdmin(caller);
        return orders.replayDeadLetters(topic, maxMessages, caller.userId());
    }

    private static void requireAdmin(Caller caller) {
        if (!caller.admin()) {
            throw new ServiceProblem(
                    "forbidden", ServiceProblem.Kind.FORBIDDEN, "This operation requires the ADMIN role", null);
        }
    }
}
