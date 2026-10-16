package com.stockforge.gateway.adapter.out.grpc;

import com.stockforge.gateway.config.GatewayProperties;
import com.stockforge.gateway.domain.model.Views;
import com.stockforge.gateway.domain.port.OrderApi;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.GetOrderRequest;
import com.stockforge.order.v1.ListOrdersRequest;
import com.stockforge.order.v1.ListOrdersResponse;
import com.stockforge.order.v1.OrderServiceGrpc;
import com.stockforge.order.v1.ReplayDeadLettersRequest;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/** Order service client: edge deadline, bulkhead and circuit breaker on every call (spec §23). */
@Component
class GrpcOrderApi implements OrderApi {

    /** Dead-letter replays read and republish many messages; they get a longer, explicit budget. */
    private static final Duration REPLAY_DEADLINE = Duration.ofSeconds(30);

    private final OrderServiceGrpc.OrderServiceBlockingStub stub;
    private final ResilientGrpcCall calls;
    private final Duration deadline;

    GrpcOrderApi(
            GrpcChannelFactory channels,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            GatewayProperties properties) {
        this.stub = OrderServiceGrpc.newBlockingStub(channels.createChannel("order"));
        this.calls = new ResilientGrpcCall("order", breakers.circuitBreaker("order"), bulkheads.bulkhead("order"));
        this.deadline = properties.deadline();
    }

    private OrderServiceGrpc.OrderServiceBlockingStub stub() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Views.Idempotent<Views.Order> placeOrder(
            String idempotencyKey, UUID userId, String reservationId, Optional<String> paymentSimulation) {
        CreateOrderResponse response = calls.execute(() -> stub().createOrder(CreateOrderRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setUserId(userId.toString())
                .setReservationId(reservationId)
                .setPaymentSimulation(paymentSimulation.orElse(""))
                .build()));
        return new Views.Idempotent<>(order(response.getOrder()), response.getIdempotentReplay());
    }

    @Override
    public Views.Order getOrder(String orderId, String requesterScope) {
        return order(calls.execute(() -> stub().getOrder(GetOrderRequest.newBuilder()
                        .setOrderId(orderId)
                        .setRequesterUserId(requesterScope)
                        .build()))
                .getOrder());
    }

    @Override
    public Views.OrderPage listOrders(UUID userId, int limit, Optional<String> cursor) {
        ListOrdersResponse response = calls.execute(() -> stub().listOrders(ListOrdersRequest.newBuilder()
                .setUserId(userId.toString())
                .setLimit(limit)
                .setCursor(cursor.orElse(""))
                .build()));
        return new Views.OrderPage(
                response.getOrdersList().stream().map(GrpcOrderApi::order).toList(),
                response.getNextCursor().isEmpty() ? Optional.empty() : Optional.of(response.getNextCursor()));
    }

    @Override
    public int replayDeadLetters(String topic, int maxMessages, UUID actorId) {
        return calls.execute(() -> stub.withDeadlineAfter(REPLAY_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                        .replayDeadLetters(ReplayDeadLettersRequest.newBuilder()
                                .setTopic(topic)
                                .setMaxMessages(maxMessages)
                                .setActorId(actorId.toString())
                                .build()))
                .getReplayed();
    }

    private static Views.Order order(com.stockforge.order.v1.Order order) {
        return new Views.Order(
                order.getId(),
                order.getUserId(),
                order.getReservationId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItemsList().stream()
                        .map(item -> new Views.OrderItem(
                                item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getCurrency()))
                        .toList(),
                order.getHistoryList().stream()
                        .map(change -> new Views.StatusChange(
                                change.getFromStatus().isEmpty() ? null : change.getFromStatus(),
                                change.getToStatus(),
                                change.getReason(),
                                change.getOccurredAt()))
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
