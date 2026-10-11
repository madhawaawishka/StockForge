package com.stockforge.gateway.domain.port;

import com.stockforge.gateway.domain.model.AvailabilityView;
import com.stockforge.gateway.domain.model.ProductView;
import com.stockforge.gateway.domain.model.ReservationView;
import java.util.List;
import java.util.Optional;

public interface InventoryClient {

    Optional<ProductView> getProduct(String id);

    List<ProductView> listProducts(String cursor, int limit);

    Optional<AvailabilityView> getAvailability(String productId);

    ReservationView reserveStock(String idempotencyKey, String productId, String userId, int quantity);

    Optional<ReservationView> getReservation(String id);

    ReservationView cancelReservation(String id, String userId);
}
