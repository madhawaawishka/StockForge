package com.stockforge.gateway.adapter.out.grpc;

import com.stockforge.gateway.config.GatewayProperties;
import com.stockforge.gateway.domain.model.Views;
import com.stockforge.gateway.domain.port.InventoryApi;
import com.stockforge.inventory.v1.AdjustStockRequest;
import com.stockforge.inventory.v1.AdjustStockResponse;
import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.CreateProductRequest;
import com.stockforge.inventory.v1.GetAvailabilityRequest;
import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.ListProductsRequest;
import com.stockforge.inventory.v1.ListProductsResponse;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * Inventory service client: every call carries the edge deadline (spec §23 A) and runs through
 * {@link ResilientGrpcCall}.
 */
@Component
class GrpcInventoryApi implements InventoryApi {

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final ResilientGrpcCall calls;
    private final Duration deadline;

    GrpcInventoryApi(
            GrpcChannelFactory channels,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            GatewayProperties properties) {
        this.stub = InventoryServiceGrpc.newBlockingStub(channels.createChannel("inventory"));
        this.calls = new ResilientGrpcCall(
                "inventory", breakers.circuitBreaker("inventory"), bulkheads.bulkhead("inventory"));
        this.deadline = properties.deadline();
    }

    private InventoryServiceGrpc.InventoryServiceBlockingStub stub() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Views.ProductPage listProducts(int limit, Optional<String> cursor) {
        ListProductsResponse response = calls.execute(() -> stub().listProducts(ListProductsRequest.newBuilder()
                .setLimit(limit)
                .setCursor(cursor.orElse(""))
                .build()));
        return new Views.ProductPage(
                response.getProductsList().stream()
                        .map(GrpcInventoryApi::product)
                        .toList(),
                response.getNextCursor().isEmpty() ? Optional.empty() : Optional.of(response.getNextCursor()));
    }

    @Override
    public Views.Product getProduct(String productId) {
        return product(calls.execute(() -> stub().getProduct(GetProductRequest.newBuilder()
                        .setProductId(productId)
                        .build()))
                .getProduct());
    }

    @Override
    public Views.Availability getAvailability(String productId) {
        return availability(calls.execute(() -> stub().getAvailability(GetAvailabilityRequest.newBuilder()
                        .setProductId(productId)
                        .build()))
                .getAvailability());
    }

    @Override
    public Views.Idempotent<Views.Reservation> reserve(
            String idempotencyKey, UUID userId, String productId, int quantity) {
        ReserveStockResponse response = calls.execute(() -> stub().reserveStock(ReserveStockRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setUserId(userId.toString())
                .setProductId(productId)
                .setQuantity(quantity)
                .build()));
        return new Views.Idempotent<>(reservation(response.getReservation()), response.getIdempotentReplay());
    }

    @Override
    public Views.Reservation getReservation(String reservationId, String requesterScope) {
        return reservation(calls.execute(() -> stub().getReservation(GetReservationRequest.newBuilder()
                        .setReservationId(reservationId)
                        .setRequesterUserId(requesterScope)
                        .build()))
                .getReservation());
    }

    @Override
    public Views.Reservation cancelReservation(String reservationId, String requesterScope) {
        return reservation(calls.execute(() -> stub().cancelReservation(CancelReservationRequest.newBuilder()
                        .setReservationId(reservationId)
                        .setRequesterUserId(requesterScope)
                        .build()))
                .getReservation());
    }

    @Override
    public Views.Product createProduct(
            String sku, String name, String priceAmount, String priceCurrency, int initialStock, UUID actorId) {
        return product(calls.execute(() -> stub().createProduct(CreateProductRequest.newBuilder()
                        .setSku(sku)
                        .setName(name)
                        .setPriceAmount(priceAmount)
                        .setPriceCurrency(priceCurrency)
                        .setInitialStock(initialStock)
                        .setActorId(actorId.toString())
                        .build()))
                .getProduct());
    }

    @Override
    public Views.Idempotent<Views.Availability> adjustStock(
            String idempotencyKey, String productId, int delta, String reason, UUID actorId) {
        AdjustStockResponse response = calls.execute(() -> stub().adjustStock(AdjustStockRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setProductId(productId)
                .setDelta(delta)
                .setReason(reason)
                .setActorId(actorId.toString())
                .build()));
        return new Views.Idempotent<>(availability(response.getAvailability()), response.getIdempotentReplay());
    }

    private static Views.Product product(com.stockforge.inventory.v1.Product product) {
        return new Views.Product(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPriceAmount(),
                product.getPriceCurrency(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private static Views.Availability availability(com.stockforge.inventory.v1.Availability availability) {
        return new Views.Availability(
                availability.getProductId(),
                availability.getTotalQuantity(),
                availability.getAvailableQuantity(),
                availability.getReservedQuantity(),
                availability.getSoldQuantity(),
                availability.getUpdatedAt(),
                availability.getApproximate());
    }

    private static Views.Reservation reservation(com.stockforge.inventory.v1.Reservation reservation) {
        return new Views.Reservation(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
