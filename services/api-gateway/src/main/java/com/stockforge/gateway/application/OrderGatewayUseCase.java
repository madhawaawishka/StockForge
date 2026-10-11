package com.stockforge.gateway.application;

import com.stockforge.gateway.domain.model.OrderItemView;
import com.stockforge.gateway.domain.model.OrderView;
import com.stockforge.gateway.domain.model.UserIdentity;
import com.stockforge.gateway.domain.port.OrderClient;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class OrderGatewayUseCase {

    private final OrderClient orderClient;

    public OrderGatewayUseCase(OrderClient orderClient) {
        this.orderClient = Objects.requireNonNull(orderClient, "orderClient must not be null");
    }

    public OrderView createOrder(
            String idempotencyKey, UserIdentity caller, String reservationId, List<OrderItemView> items) {
        Objects.requireNonNull(caller, "caller must not be null");
        return orderClient.createOrder(idempotencyKey, caller.userId().toString(), reservationId, items);
    }

    public Optional<OrderView> getOrder(String id, UserIdentity caller) {
        Objects.requireNonNull(caller, "caller must not be null");
        Optional<OrderView> order = orderClient.getOrder(id);
        if (order.isEmpty()) {
            return Optional.empty();
        }
        OrderView view = order.get();
        // BOLA defense (spec §28): return 404/empty rather than 403 if the user does not own this resource
        if (!caller.isAdmin() && !view.userId().equals(caller.userId().toString())) {
            return Optional.empty();
        }
        return Optional.of(view);
    }

    public List<OrderView> listOrders(UserIdentity caller, int limit) {
        Objects.requireNonNull(caller, "caller must not be null");
        return orderClient.listOrders(caller.userId().toString(), limit);
    }
}
