package com.stockforge.gateway.adapter.out.grpc;

import com.stockforge.gateway.domain.model.AvailabilityView;
import com.stockforge.gateway.domain.model.ProductView;
import com.stockforge.gateway.domain.model.ReservationView;
import com.stockforge.gateway.domain.port.InventoryClient;
import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.CancelReservationResponse;
import com.stockforge.inventory.v1.GetAvailabilityRequest;
import com.stockforge.inventory.v1.GetAvailabilityResponse;
import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetProductResponse;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.GetReservationResponse;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.ListProductsRequest;
import com.stockforge.inventory.v1.ListProductsResponse;
import com.stockforge.inventory.v1.ProductDto;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GrpcInventoryClient implements InventoryClient {

    private static final long DEADLINE_SECONDS = 2;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    public GrpcInventoryClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
    }

    private InventoryServiceGrpc.InventoryServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public Optional<ProductView> getProduct(String id) {
        try {
            GetProductResponse response = withDeadline()
                    .getProduct(GetProductRequest.newBuilder().setProductId(id).build());
            return Optional.of(toProductView(response.getProduct()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<ProductView> listProducts(String cursor, int limit) {
        ListProductsRequest.Builder req = ListProductsRequest.newBuilder().setLimit(limit);
        if (cursor != null && !cursor.isBlank()) {
            req.setCursor(cursor);
        }
        ListProductsResponse response = withDeadline().listProducts(req.build());
        List<ProductView> result = new ArrayList<>();
        for (ProductDto dto : response.getProductsList()) {
            result.add(toProductView(dto));
        }
        return result;
    }

    @Override
    public Optional<AvailabilityView> getAvailability(String productId) {
        try {
            GetAvailabilityResponse response = withDeadline()
                    .getAvailability(GetAvailabilityRequest.newBuilder()
                            .setProductId(productId)
                            .build());
            return Optional.of(new AvailabilityView(response.getProductId(), response.getAvailableQuantity(), false));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public ReservationView reserveStock(String idempotencyKey, String productId, String userId, int quantity) {
        ReserveStockRequest request = ReserveStockRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setProductId(productId)
                .setUserId(userId)
                .setQuantity(quantity)
                .build();
        ReserveStockResponse response = withDeadline().reserveStock(request);
        return new ReservationView(
                response.getReservationId(),
                productId,
                userId,
                quantity,
                response.getStatus(),
                response.getExpiresAt());
    }

    @Override
    public Optional<ReservationView> getReservation(String id) {
        try {
            GetReservationResponse response = withDeadline()
                    .getReservation(GetReservationRequest.newBuilder()
                            .setReservationId(id)
                            .build());
            return Optional.of(new ReservationView(
                    response.getReservationId(),
                    response.getProductId(),
                    response.getUserId(),
                    response.getQuantity(),
                    response.getStatus(),
                    response.getExpiresAt()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public ReservationView cancelReservation(String id, String userId) {
        CancelReservationResponse response = withDeadline()
                .cancelReservation(CancelReservationRequest.newBuilder()
                        .setReservationId(id)
                        .setUserId(userId)
                        .build());
        return new ReservationView(response.getReservationId(), "", userId, 0, response.getStatus(), "");
    }

    private static ProductView toProductView(ProductDto dto) {
        return new ProductView(
                dto.getId(),
                dto.getSku(),
                dto.getName(),
                dto.getPriceAmount(),
                dto.getPriceCurrency(),
                dto.getAvailableQuantity());
    }
}
