package com.stockforge.order.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stockforge.order.application.CancelOrderUseCase;
import com.stockforge.order.application.CreateOrderCommand;
import com.stockforge.order.application.CreateOrderResult;
import com.stockforge.order.application.CreateOrderUseCase;
import com.stockforge.order.application.GetOrderUseCase;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.v1.CancelOrderRequest;
import com.stockforge.order.v1.CancelOrderResponse;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.GetOrderRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderGrpcServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final OrderId ORDER_ID = OrderId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-4a0b-7c09-8d21-0a1b2c3d4e5f"));

    private CreateOrderUseCase createOrderUseCase;
    private GetOrderUseCase getOrderUseCase;
    private CancelOrderUseCase cancelOrderUseCase;
    private OrderGrpcService grpcService;

    @BeforeEach
    void setUp() {
        createOrderUseCase = mock(CreateOrderUseCase.class);
        getOrderUseCase = mock(GetOrderUseCase.class);
        cancelOrderUseCase = mock(CancelOrderUseCase.class);
        grpcService = new OrderGrpcService(createOrderUseCase, getOrderUseCase, cancelOrderUseCase);
    }

    @Test
    void createOrderSuccess() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("99.99", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);
        when(createOrderUseCase.execute(any(CreateOrderCommand.class)))
                .thenReturn(new CreateOrderResult.Created(order));

        CreateOrderRequest request = CreateOrderRequest.newBuilder()
                .setUserId(USER_ID.value().toString())
                .setReservationId(RESERVATION_ID.value().toString())
                .setIdempotencyKey("key-1")
                .build();

        AtomicReference<CreateOrderResponse> responseRef = new AtomicReference<>();
        grpcService.createOrder(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(CreateOrderResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getOrderId()).isEqualTo(ORDER_ID.value().toString());
        assertThat(responseRef.get().getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void getOrderEnforcesObjectLevelAuthorization() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("99.99", "USD"));
        Order order = Order.create(ORDER_ID, USER_ID, RESERVATION_ID, List.of(item), NOW);
        when(getOrderUseCase.execute(ORDER_ID)).thenReturn(Optional.of(order));

        UserId otherUser = UserId.of(UUID.fromString("0192f5c4-9999-7000-8000-000000000000"));
        GetOrderRequest request = GetOrderRequest.newBuilder()
                .setOrderId(ORDER_ID.value().toString())
                .setUserId(otherUser.value().toString())
                .build();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        grpcService.getOrder(request, new SimpleStreamObserver<>() {
            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
            }
        });

        assertThat(errorRef.get()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException ex = (StatusRuntimeException) errorRef.get();
        // BOLA defense (§28): return NOT_FOUND so attacker cannot confirm existence
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void cancelOrderSuccess() {
        when(cancelOrderUseCase.execute(ORDER_ID, "User cancelled")).thenReturn(true);

        CancelOrderRequest request = CancelOrderRequest.newBuilder()
                .setOrderId(ORDER_ID.value().toString())
                .setUserId(USER_ID.value().toString())
                .setReason("User cancelled")
                .build();

        AtomicReference<CancelOrderResponse> responseRef = new AtomicReference<>();
        grpcService.cancelOrder(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(CancelOrderResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getStatus()).isEqualTo("CANCELLED");
    }

    private static class SimpleStreamObserver<T> implements StreamObserver<T> {
        @Override
        public void onNext(T value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
