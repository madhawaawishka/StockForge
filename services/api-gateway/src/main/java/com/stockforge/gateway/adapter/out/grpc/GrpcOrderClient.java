package com.stockforge.gateway.adapter.out.grpc;

import com.stockforge.gateway.domain.model.OrderItemView;
import com.stockforge.gateway.domain.model.OrderView;
import com.stockforge.gateway.domain.port.OrderClient;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.GetOrderRequest;
import com.stockforge.order.v1.GetOrderResponse;
import com.stockforge.order.v1.ListOrdersRequest;
import com.stockforge.order.v1.ListOrdersResponse;
import com.stockforge.order.v1.OrderItemDto;
import com.stockforge.order.v1.OrderServiceGrpc;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GrpcOrderClient implements OrderClient {

    private static final long DEADLINE_SECONDS = 2;
    private final OrderServiceGrpc.OrderServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public GrpcOrderClient(
            OrderServiceGrpc.OrderServiceBlockingStub stub,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
            ObjectProvider<BulkheadRegistry> bulkheadRegistryProvider) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        CircuitBreakerRegistry cbRegistry =
                circuitBreakerRegistryProvider != null ? circuitBreakerRegistryProvider.getIfAvailable() : null;
        this.circuitBreaker = cbRegistry != null
                ? cbRegistry.circuitBreaker("orderService")
                : CircuitBreaker.ofDefaults("orderService");
        BulkheadRegistry bhRegistry =
                bulkheadRegistryProvider != null ? bulkheadRegistryProvider.getIfAvailable() : null;
        this.bulkhead = bhRegistry != null ? bhRegistry.bulkhead("orderService") : Bulkhead.ofDefaults("orderService");
    }

    public GrpcOrderClient(OrderServiceGrpc.OrderServiceBlockingStub stub) {
        this(stub, null, null);
    }

    private OrderServiceGrpc.OrderServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    private <T> T execute(Supplier<T> supplier) {
        return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, supplier))
                .get();
    }

    @Override
    public OrderView createOrder(
            String idempotencyKey, String userId, String reservationId, List<OrderItemView> items) {
        CreateOrderRequest request = CreateOrderRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setUserId(userId)
                .setReservationId(reservationId)
                .build();
        CreateOrderResponse response = execute(() -> withDeadline().createOrder(request));
        return new OrderView(
                response.getOrderId(),
                response.getUserId(),
                response.getReservationId(),
                response.getStatus(),
                response.getTotalAmount(),
                response.getCurrency(),
                items,
                response.getCreatedAt());
    }

    @Override
    public Optional<OrderView> getOrder(String id) {
        try {
            GetOrderResponse response = execute(() -> withDeadline()
                    .getOrder(GetOrderRequest.newBuilder().setOrderId(id).build()));
            return Optional.of(toOrderView(response));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<OrderView> listOrders(String userId, int limit) {
        ListOrdersResponse response = execute(() -> withDeadline()
                .listOrders(ListOrdersRequest.newBuilder()
                        .setUserId(userId)
                        .setLimit(limit)
                        .build()));
        List<OrderView> result = new ArrayList<>();
        for (GetOrderResponse orderResp : response.getOrdersList()) {
            result.add(toOrderView(orderResp));
        }
        return result;
    }

    private static OrderView toOrderView(GetOrderResponse resp) {
        List<OrderItemView> items = new ArrayList<>();
        for (OrderItemDto itemDto : resp.getItemsList()) {
            items.add(new OrderItemView(
                    itemDto.getProductId(), itemDto.getQuantity(), itemDto.getUnitPrice(), itemDto.getCurrency()));
        }
        return new OrderView(
                resp.getOrderId(),
                resp.getUserId(),
                resp.getReservationId(),
                resp.getStatus(),
                resp.getTotalAmount(),
                resp.getCurrency(),
                items,
                resp.getCreatedAt());
    }
}
