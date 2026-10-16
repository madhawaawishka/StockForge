package com.stockforge.gateway.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Read models the gateway returns to clients. They are the public API's shape, decoupled from the internal gRPC
 * messages so the internal contracts can evolve without breaking clients (spec §12). Amounts are decimal strings and
 * timestamps RFC 3339 strings, exactly as the services produce them.
 */
public final class Views {

    private Views() {}

    public record Product(
            String id,
            String sku,
            String name,
            String priceAmount,
            String priceCurrency,
            String status,
            String createdAt,
            String updatedAt) {}

    public record ProductPage(List<Product> items, Optional<String> nextCursor) {

        public ProductPage {
            items = List.copyOf(items);
        }
    }

    /** {@code approximate} is true when served from a seconds-old cache (spec §17). */
    public record Availability(
            String productId,
            int totalQuantity,
            int availableQuantity,
            int reservedQuantity,
            int soldQuantity,
            String asOf,
            boolean approximate) {}

    public record Reservation(
            String id,
            String userId,
            String productId,
            int quantity,
            String status,
            String expiresAt,
            String createdAt,
            String updatedAt) {}

    public record OrderItem(String productId, int quantity, String unitPrice, String currency) {}

    public record StatusChange(String fromStatus, String toStatus, String reason, String occurredAt) {}

    public record Order(
            String id,
            String userId,
            String reservationId,
            String status,
            String totalAmount,
            String currency,
            List<OrderItem> items,
            List<StatusChange> history,
            String createdAt,
            String updatedAt) {

        public Order {
            items = List.copyOf(items);
            history = List.copyOf(history);
        }
    }

    public record OrderPage(List<Order> items, Optional<String> nextCursor) {

        public OrderPage {
            items = List.copyOf(items);
        }
    }

    /** A result of an idempotent write: {@code replayed} when an earlier request with the same key produced it. */
    public record Idempotent<T>(T value, boolean replayed) {}
}
