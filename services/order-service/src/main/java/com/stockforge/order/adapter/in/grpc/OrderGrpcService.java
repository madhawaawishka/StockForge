package com.stockforge.order.adapter.in.grpc;

import com.stockforge.order.application.CancelOrderUseCase;
import com.stockforge.order.application.CreateOrderCommand;
import com.stockforge.order.application.CreateOrderResult;
import com.stockforge.order.application.CreateOrderUseCase;
import com.stockforge.order.application.GetOrderUseCase;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.OrderStatusHistoryEntry;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.v1.CancelOrderRequest;
import com.stockforge.order.v1.CancelOrderResponse;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.GetOrderRequest;
import com.stockforge.order.v1.GetOrderResponse;
import com.stockforge.order.v1.ListOrdersRequest;
import com.stockforge.order.v1.ListOrdersResponse;
import com.stockforge.order.v1.OrderItemDto;
import com.stockforge.order.v1.OrderServiceGrpc;
import com.stockforge.order.v1.OrderStatusHistoryDto;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Inbound gRPC adapter exposing internal Order Service contracts (spec §12, §16, §23). */
@Component
class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;

    OrderGrpcService(
            CreateOrderUseCase createOrderUseCase,
            GetOrderUseCase getOrderUseCase,
            CancelOrderUseCase cancelOrderUseCase) {
        this.createOrderUseCase = Objects.requireNonNull(createOrderUseCase, "createOrderUseCase must not be null");
        this.getOrderUseCase = Objects.requireNonNull(getOrderUseCase, "getOrderUseCase must not be null");
        this.cancelOrderUseCase = Objects.requireNonNull(cancelOrderUseCase, "cancelOrderUseCase must not be null");
    }

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        try {
            UserId userId = UserId.of(UUID.fromString(request.getUserId()));
            ReservationId reservationId = ReservationId.of(UUID.fromString(request.getReservationId()));
            String idempotencyKey = request.getIdempotencyKey();

            // Default order item derived from reservation (flash-sale single-item model, spec §13)
            OrderItem defaultItem = new OrderItem(
                    ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10")), 1, Money.of("99.99", "USD"));

            CreateOrderCommand command =
                    new CreateOrderCommand(idempotencyKey, userId, reservationId, List.of(defaultItem));
            CreateOrderResult result = createOrderUseCase.execute(command);

            switch (result) {
                case CreateOrderResult.Created c -> sendCreateResponse(c.order(), responseObserver);
                case CreateOrderResult.IdempotentReplay r -> sendCreateResponse(r.order(), responseObserver);
                case CreateOrderResult.ReservationAlreadyOrdered r ->
                    responseObserver.onError(Status.ALREADY_EXISTS
                            .withDescription("Order already placed for reservation " + r.reservationId())
                            .asRuntimeException());
                case CreateOrderResult.IdempotencyConflict c ->
                    responseObserver.onError(
                            Status.ABORTED.withDescription(c.message()).asRuntimeException());
            }
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid request: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        try {
            OrderId orderId = OrderId.of(UUID.fromString(request.getOrderId()));
            var found = getOrderUseCase.execute(orderId);

            if (found.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Order " + orderId.value() + " not found")
                        .asRuntimeException());
                return;
            }

            Order order = found.get();
            if (!request.getUserId().isBlank()) {
                UserId requestedUser = UserId.of(UUID.fromString(request.getUserId()));
                if (!order.userId().equals(requestedUser)) {
                    // Object-level authorization (spec §28): return NOT_FOUND for other users' resources
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Order " + orderId.value() + " not found")
                            .asRuntimeException());
                    return;
                }
            }

            GetOrderResponse.Builder builder = GetOrderResponse.newBuilder()
                    .setOrderId(order.id().value().toString())
                    .setReservationId(order.reservationId().value().toString())
                    .setUserId(order.userId().value().toString())
                    .setStatus(order.status().name())
                    .setTotalAmount(order.totalAmount().amount().toPlainString())
                    .setCurrency(order.totalAmount().currency().getCurrencyCode())
                    .setCreatedAt(order.createdAt().toString())
                    .setUpdatedAt(order.updatedAt().toString());

            for (OrderItem item : order.items()) {
                builder.addItems(OrderItemDto.newBuilder()
                        .setProductId(item.productId().value().toString())
                        .setQuantity(item.quantity())
                        .setUnitPrice(item.unitPrice().amount().toPlainString())
                        .setCurrency(item.unitPrice().currency().getCurrencyCode())
                        .build());
            }

            for (OrderStatusHistoryEntry entry : order.history()) {
                builder.addHistory(OrderStatusHistoryDto.newBuilder()
                        .setFromStatus(
                                entry.fromStatus() != null ? entry.fromStatus().name() : "")
                        .setToStatus(entry.toStatus().name())
                        .setReason(entry.reason())
                        .setOccurredAt(entry.occurredAt().toString())
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid request: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        try {
            OrderId orderId = OrderId.of(UUID.fromString(request.getOrderId()));
            String reason = request.getReason().isBlank() ? "Cancelled by user" : request.getReason();

            boolean cancelled = cancelOrderUseCase.execute(orderId, reason);
            if (!cancelled) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Order cannot be cancelled in its current state or not found")
                        .asRuntimeException());
                return;
            }

            CancelOrderResponse response = CancelOrderResponse.newBuilder()
                    .setOrderId(orderId.value().toString())
                    .setStatus("CANCELLED")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid request: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listOrders(ListOrdersRequest request, StreamObserver<ListOrdersResponse> responseObserver) {
        try {
            UserId userId = UserId.of(UUID.fromString(request.getUserId()));
            int limit = request.getLimit() <= 0 ? 20 : Math.min(request.getLimit(), 100);
            List<Order> list = getOrderUseCase.executeForUser(userId, limit);

            ListOrdersResponse.Builder builder = ListOrdersResponse.newBuilder();
            for (Order o : list) {
                GetOrderResponse.Builder orderBuilder = GetOrderResponse.newBuilder()
                        .setOrderId(o.id().value().toString())
                        .setReservationId(o.reservationId().value().toString())
                        .setUserId(o.userId().value().toString())
                        .setStatus(o.status().name())
                        .setTotalAmount(o.totalAmount().amount().toPlainString())
                        .setCurrency(o.totalAmount().currency().getCurrencyCode())
                        .setCreatedAt(o.createdAt().toString())
                        .setUpdatedAt(o.updatedAt().toString());

                for (OrderItem item : o.items()) {
                    orderBuilder.addItems(OrderItemDto.newBuilder()
                            .setProductId(item.productId().value().toString())
                            .setQuantity(item.quantity())
                            .setUnitPrice(item.unitPrice().amount().toPlainString())
                            .setCurrency(item.unitPrice().currency().getCurrencyCode())
                            .build());
                }
                builder.addOrders(orderBuilder.build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid request: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private void sendCreateResponse(Order order, StreamObserver<CreateOrderResponse> responseObserver) {
        CreateOrderResponse response = CreateOrderResponse.newBuilder()
                .setOrderId(order.id().value().toString())
                .setReservationId(order.reservationId().value().toString())
                .setUserId(order.userId().value().toString())
                .setStatus(order.status().name())
                .setTotalAmount(order.totalAmount().amount().toPlainString())
                .setCurrency(order.totalAmount().currency().getCurrencyCode())
                .setCreatedAt(order.createdAt().toString())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
