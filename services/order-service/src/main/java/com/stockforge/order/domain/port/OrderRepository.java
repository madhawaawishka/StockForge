package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    void insert(Order order);

    Optional<Order> findById(OrderId id);

    Optional<Order> findByReservationId(ReservationId reservationId);

    List<Order> findByUserId(UserId userId, int limit);

    boolean updateStatusIfCurrent(
            OrderId id, OrderStatus currentStatus, OrderStatus newStatus, String reason, Instant now);

    List<Order> findStuckPendingOrders(Instant createdBefore, int limit);
}
