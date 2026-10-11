package com.stockforge.gateway.domain.port;

import com.stockforge.gateway.domain.model.OrderItemView;
import com.stockforge.gateway.domain.model.OrderView;
import java.util.List;
import java.util.Optional;

public interface OrderClient {

    OrderView createOrder(String idempotencyKey, String userId, String reservationId, List<OrderItemView> items);

    Optional<OrderView> getOrder(String id);

    List<OrderView> listOrders(String userId, int limit);
}
