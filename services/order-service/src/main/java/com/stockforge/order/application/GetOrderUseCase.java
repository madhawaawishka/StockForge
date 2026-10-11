package com.stockforge.order.application;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.port.OrderRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Queries order details and history (spec §12, §13). */
@Transactional(readOnly = true)
public class GetOrderUseCase {

    private final OrderRepository orders;

    public GetOrderUseCase(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
    }

    public Optional<Order> execute(OrderId id) {
        return orders.findById(id);
    }

    public java.util.List<Order> executeForUser(com.stockforge.order.domain.model.UserId userId, int limit) {
        return orders.findByUserId(userId, limit);
    }
}
