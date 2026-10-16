package com.stockforge.gateway.domain.port;

import com.stockforge.gateway.domain.model.Views;
import java.util.Optional;
import java.util.UUID;

/**
 * The Inventory service as the gateway uses it. Implementations make remote calls with a deadline and report failures
 * as {@link com.stockforge.gateway.domain.model.ServiceProblem}.
 */
public interface InventoryApi {

    Views.ProductPage listProducts(int limit, Optional<String> cursor);

    Views.Product getProduct(String productId);

    Views.Availability getAvailability(String productId);

    Views.Idempotent<Views.Reservation> reserve(String idempotencyKey, UUID userId, String productId, int quantity);

    /** {@code requesterScope} as in {@link com.stockforge.gateway.domain.model.Caller#requesterScope()}. */
    Views.Reservation getReservation(String reservationId, String requesterScope);

    Views.Reservation cancelReservation(String reservationId, String requesterScope);

    Views.Product createProduct(
            String sku, String name, String priceAmount, String priceCurrency, int initialStock, UUID actorId);

    Views.Idempotent<Views.Availability> adjustStock(
            String idempotencyKey, String productId, int delta, String reason, UUID actorId);
}
