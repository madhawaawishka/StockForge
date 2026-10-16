package com.stockforge.order.application;

import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OrderRepository.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Order status, history and the caller's order list (spec §12). Reads the order database: read-your-writes. */
public class OrderQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;

    public OrderQueries(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
    }

    /**
     * The order, if the requester may see it.
     *
     * @param requester the authenticated caller; another user's order is reported as absent so ids cannot be probed
     *     (spec §28). Empty for privileged callers.
     */
    public Optional<Order> find(OrderId id, Optional<UserId> requester) {
        return orders.findById(id)
                .filter(order -> requester.isEmpty() || order.userId().equals(requester.get()));
    }

    /** One page of the user's orders, newest first. */
    public OrderPage list(UserId userId, Optional<PageKey> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<Order> rows = orders.findByUser(userId, after, limit + 1);
        if (rows.size() <= limit) {
            return new OrderPage(rows, Optional.empty());
        }
        List<Order> page = rows.subList(0, limit);
        Order last = page.getLast();
        return new OrderPage(
                page, Optional.of(new PageKey(last.createdAt(), last.id().value())));
    }

    /**
     * One page of orders.
     *
     * @param next keyset position to continue after, present only when more orders exist
     */
    public record OrderPage(List<Order> orders, Optional<PageKey> next) {

        public OrderPage {
            orders = List.copyOf(orders);
            Objects.requireNonNull(next, "next must not be null");
        }
    }
}
