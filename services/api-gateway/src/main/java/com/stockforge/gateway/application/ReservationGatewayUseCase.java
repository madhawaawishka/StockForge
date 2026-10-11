package com.stockforge.gateway.application;

import com.stockforge.gateway.domain.model.ReservationView;
import com.stockforge.gateway.domain.model.UserIdentity;
import com.stockforge.gateway.domain.port.InventoryClient;
import java.util.Objects;
import java.util.Optional;

public class ReservationGatewayUseCase {

    private final InventoryClient inventoryClient;

    public ReservationGatewayUseCase(InventoryClient inventoryClient) {
        this.inventoryClient = Objects.requireNonNull(inventoryClient, "inventoryClient must not be null");
    }

    public ReservationView reserve(String idempotencyKey, String productId, UserIdentity caller, int quantity) {
        Objects.requireNonNull(caller, "caller must not be null");
        return inventoryClient.reserveStock(
                idempotencyKey, productId, caller.userId().toString(), quantity);
    }

    public Optional<ReservationView> getReservation(String id, UserIdentity caller) {
        Objects.requireNonNull(caller, "caller must not be null");
        Optional<ReservationView> reservation = inventoryClient.getReservation(id);
        if (reservation.isEmpty()) {
            return Optional.empty();
        }
        ReservationView view = reservation.get();
        // BOLA defense (spec §28): return 404/empty rather than 403 if the user does not own this resource
        if (!caller.isAdmin() && !view.userId().equals(caller.userId().toString())) {
            return Optional.empty();
        }
        return Optional.of(view);
    }

    public Optional<ReservationView> cancelReservation(String id, UserIdentity caller) {
        Objects.requireNonNull(caller, "caller must not be null");
        Optional<ReservationView> existing = inventoryClient.getReservation(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!caller.isAdmin() && !existing.get().userId().equals(caller.userId().toString())) {
            return Optional.empty();
        }
        return Optional.of(inventoryClient.cancelReservation(id, caller.userId().toString()));
    }
}
