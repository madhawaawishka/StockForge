package com.stockforge.gateway.testsupport;

import com.stockforge.grpc.GrpcProblems;
import com.stockforge.inventory.v1.AdjustStockRequest;
import com.stockforge.inventory.v1.AdjustStockResponse;
import com.stockforge.inventory.v1.Availability;
import com.stockforge.inventory.v1.CreateProductRequest;
import com.stockforge.inventory.v1.CreateProductResponse;
import com.stockforge.inventory.v1.GetAvailabilityRequest;
import com.stockforge.inventory.v1.GetAvailabilityResponse;
import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetProductResponse;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.GetReservationResponse;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.Product;
import com.stockforge.inventory.v1.Reservation;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.Order;
import com.stockforge.order.v1.OrderServiceGrpc;
import com.stockforge.order.v1.ReplayDeadLettersRequest;
import com.stockforge.order.v1.ReplayDeadLettersResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * In-process stand-ins for the Inventory and Order services, honouring their contracts (owner checks, problem-type
 * trailers). Special product ids trigger specific outcomes; requests are recorded so tests can check what the gateway
 * sent downstream.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeDownstreams {

    public static final String PRODUCT = "0192f5c4-0000-7000-8000-000000000001";
    public static final String SOLD_OUT = "0192f5c4-0000-7000-8000-000000000002";
    public static final String OVER_LIMIT = "0192f5c4-0000-7000-8000-000000000003";
    public static final String BROKEN = "0192f5c4-0000-7000-8000-000000000004";

    @Bean
    Inventory fakeInventory() {
        return new Inventory();
    }

    @Bean
    Orders fakeOrders() {
        return new Orders();
    }

    public static class Inventory extends InventoryServiceGrpc.InventoryServiceImplBase {

        public final List<ReserveStockRequest> reserveRequests = new CopyOnWriteArrayList<>();
        public final List<String> adjustedBy = new CopyOnWriteArrayList<>();
        private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

        @Override
        public void getProduct(GetProductRequest request, StreamObserver<GetProductResponse> response) {
            if (!PRODUCT.equals(request.getProductId())) {
                response.onError(GrpcProblems.problem(Status.NOT_FOUND, GrpcProblems.PRODUCT_NOT_FOUND, "Unknown"));
                return;
            }
            response.onNext(
                    GetProductResponse.newBuilder().setProduct(product()).build());
            response.onCompleted();
        }

        @Override
        public void reserveStock(ReserveStockRequest request, StreamObserver<ReserveStockResponse> response) {
            reserveRequests.add(request);
            switch (request.getProductId()) {
                case SOLD_OUT ->
                    response.onError(GrpcProblems.problem(
                            Status.FAILED_PRECONDITION, GrpcProblems.INSUFFICIENT_STOCK, "Sold out"));
                case OVER_LIMIT ->
                    response.onError(GrpcProblems.problem(
                            Status.FAILED_PRECONDITION, GrpcProblems.PURCHASE_LIMIT_EXCEEDED, "Limit is 2"));
                case BROKEN -> response.onError(Status.UNAVAILABLE.asRuntimeException());
                default -> {
                    Reservation reservation = Reservation.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setUserId(request.getUserId())
                            .setProductId(request.getProductId())
                            .setQuantity(request.getQuantity())
                            .setStatus("PENDING")
                            .setExpiresAt("2026-10-08T10:25:30Z")
                            .setCreatedAt("2026-10-08T10:15:30Z")
                            .setUpdatedAt("2026-10-08T10:15:30Z")
                            .build();
                    reservations.put(reservation.getId(), reservation);
                    response.onNext(ReserveStockResponse.newBuilder()
                            .setReservation(reservation)
                            .build());
                    response.onCompleted();
                }
            }
        }

        @Override
        public void getReservation(GetReservationRequest request, StreamObserver<GetReservationResponse> response) {
            Reservation reservation = reservations.get(request.getReservationId());
            // The real service's owner check: another user's reservation is NOT_FOUND.
            if (reservation == null
                    || (!request.getRequesterUserId().isEmpty()
                            && !reservation.getUserId().equals(request.getRequesterUserId()))) {
                response.onError(GrpcProblems.problem(
                        Status.NOT_FOUND, GrpcProblems.RESERVATION_NOT_FOUND, "Reservation does not exist"));
                return;
            }
            response.onNext(GetReservationResponse.newBuilder()
                    .setReservation(reservation)
                    .build());
            response.onCompleted();
        }

        @Override
        public void createProduct(CreateProductRequest request, StreamObserver<CreateProductResponse> response) {
            response.onNext(CreateProductResponse.newBuilder()
                    .setProduct(product().toBuilder().setSku(request.getSku()))
                    .setAvailability(availability())
                    .build());
            response.onCompleted();
        }

        private final AtomicInteger availabilityReads = new AtomicInteger();

        /** Like the real service: the first read misses the cache (exact), later reads are served from it. */
        @Override
        public void getAvailability(GetAvailabilityRequest request, StreamObserver<GetAvailabilityResponse> response) {
            boolean cached = availabilityReads.getAndIncrement() > 0;
            response.onNext(GetAvailabilityResponse.newBuilder()
                    .setAvailability(availability().toBuilder().setApproximate(cached))
                    .build());
            response.onCompleted();
        }

        @Override
        public void adjustStock(AdjustStockRequest request, StreamObserver<AdjustStockResponse> response) {
            adjustedBy.add(request.getActorId());
            response.onNext(AdjustStockResponse.newBuilder()
                    .setAvailability(availability())
                    .build());
            response.onCompleted();
        }

        private static Product product() {
            return Product.newBuilder()
                    .setId(PRODUCT)
                    .setSku("SNEAKER-1")
                    .setName("Sneaker")
                    .setPriceAmount("19.99")
                    .setPriceCurrency("USD")
                    .setStatus("ACTIVE")
                    .setCreatedAt("2026-10-08T10:00:00Z")
                    .setUpdatedAt("2026-10-08T10:00:00Z")
                    .build();
        }

        private static Availability availability() {
            return Availability.newBuilder()
                    .setProductId(PRODUCT)
                    .setTotalQuantity(10)
                    .setAvailableQuantity(10)
                    .setUpdatedAt("2026-10-08T10:00:00Z")
                    .build();
        }
    }

    public static class Orders extends OrderServiceGrpc.OrderServiceImplBase {

        public final List<CreateOrderRequest> createRequests = new CopyOnWriteArrayList<>();

        @Override
        public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> response) {
            createRequests.add(request);
            response.onNext(CreateOrderResponse.newBuilder()
                    .setOrder(Order.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setUserId(request.getUserId())
                            .setReservationId(request.getReservationId())
                            .setStatus("PENDING_PAYMENT")
                            .setTotalAmount("19.99")
                            .setCurrency("USD")
                            .setCreatedAt("2026-10-08T10:15:30Z")
                            .setUpdatedAt("2026-10-08T10:15:30Z"))
                    .build());
            response.onCompleted();
        }

        @Override
        public void replayDeadLetters(
                ReplayDeadLettersRequest request, StreamObserver<ReplayDeadLettersResponse> response) {
            response.onNext(
                    ReplayDeadLettersResponse.newBuilder().setReplayed(3).build());
            response.onCompleted();
        }
    }
}
