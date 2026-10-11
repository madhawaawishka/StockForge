package com.stockforge.order.adapter.in.web;

import com.stockforge.order.application.GetOrderUseCase;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.port.OrderRepository;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final GetOrderUseCase getOrderUseCase;
    private final OrderRepository orderRepository;

    OrderController(GetOrderUseCase getOrderUseCase, OrderRepository orderRepository) {
        this.getOrderUseCase = Objects.requireNonNull(getOrderUseCase, "getOrderUseCase must not be null");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
    }

    @GetMapping("/{id}")
    ResponseEntity<?> getOrder(
            @PathVariable("id") UUID id, @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        OrderId orderId = OrderId.of(id);
        Optional<Order> found = getOrderUseCase.execute(orderId);

        if (found.isEmpty()) {
            return ResponseEntity.of(
                    problemDetail(HttpStatus.NOT_FOUND, "Order not found", "/problems/order-not-found"));
        }

        Order order = found.get();
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            UUID headerUser = UUID.fromString(userIdHeader);
            if (!order.userId().value().equals(headerUser)) {
                // Object-level authorization (spec §28)
                return ResponseEntity.of(
                        problemDetail(HttpStatus.NOT_FOUND, "Order not found", "/problems/order-not-found"));
            }
        }

        return ResponseEntity.ok(toResponse(order));
    }

    @GetMapping
    ResponseEntity<?> listOrders(
            @RequestHeader("X-User-Id") UUID userId, @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<Order> orders = orderRepository.findByUserId(com.stockforge.order.domain.model.UserId.of(userId), limit);
        return ResponseEntity.ok(
                orders.stream().map(OrderController::toResponse).toList());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.of(problemDetail(HttpStatus.BAD_REQUEST, e.getMessage(), "/problems/invalid-argument"));
    }

    private static Optional<ProblemDetail> problemDetail(HttpStatus status, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://example.com" + type));
        return Optional.of(problem);
    }

    private static OrderResponse toResponse(Order o) {
        List<ItemResponse> items = o.items().stream()
                .map(i -> new ItemResponse(
                        i.productId().value().toString(),
                        i.quantity(),
                        i.unitPrice().amount().toPlainString(),
                        i.unitPrice().currency().getCurrencyCode()))
                .toList();

        List<HistoryResponse> history = o.history().stream()
                .map(h -> new HistoryResponse(
                        h.fromStatus() != null ? h.fromStatus().name() : null,
                        h.toStatus().name(),
                        h.reason(),
                        h.occurredAt().toString()))
                .toList();

        return new OrderResponse(
                o.id().value().toString(),
                o.userId().value().toString(),
                o.reservationId().value().toString(),
                o.status().name(),
                o.totalAmount().amount().toPlainString(),
                o.totalAmount().currency().getCurrencyCode(),
                items,
                history,
                o.createdAt().toString(),
                o.updatedAt().toString());
    }

    record OrderResponse(
            String id,
            String userId,
            String reservationId,
            String status,
            String totalAmount,
            String currency,
            List<ItemResponse> items,
            List<HistoryResponse> history,
            String createdAt,
            String updatedAt) {}

    record ItemResponse(String productId, int quantity, String unitPrice, String currency) {}

    record HistoryResponse(String fromStatus, String toStatus, String reason, String occurredAt) {}
}
