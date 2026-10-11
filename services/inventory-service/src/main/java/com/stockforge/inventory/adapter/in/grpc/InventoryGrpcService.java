package com.stockforge.inventory.adapter.in.grpc;

import com.stockforge.inventory.application.CancelReservationResult;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.ConfirmReservationResult;
import com.stockforge.inventory.application.ConfirmReservationUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ReserveStockCommand;
import com.stockforge.inventory.application.ReserveStockResult;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.CancelReservationResponse;
import com.stockforge.inventory.v1.ConfirmReservationRequest;
import com.stockforge.inventory.v1.ConfirmReservationResponse;
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
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Inbound gRPC adapter exposing internal inventory contracts (spec §12, §23). */
@Component
class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final ReserveStockUseCase reserveStockUseCase;
    private final CancelReservationUseCase cancelReservationUseCase;
    private final ConfirmReservationUseCase confirmReservationUseCase;
    private final ProductCatalogQueries productCatalogQueries;
    private final GetReservationUseCase getReservationUseCase;

    InventoryGrpcService(
            ReserveStockUseCase reserveStockUseCase,
            CancelReservationUseCase cancelReservationUseCase,
            ConfirmReservationUseCase confirmReservationUseCase,
            ProductCatalogQueries productCatalogQueries,
            GetReservationUseCase getReservationUseCase) {
        this.reserveStockUseCase = Objects.requireNonNull(reserveStockUseCase, "reserveStockUseCase must not be null");
        this.cancelReservationUseCase =
                Objects.requireNonNull(cancelReservationUseCase, "cancelReservationUseCase must not be null");
        this.confirmReservationUseCase =
                Objects.requireNonNull(confirmReservationUseCase, "confirmReservationUseCase must not be null");
        this.productCatalogQueries =
                Objects.requireNonNull(productCatalogQueries, "productCatalogQueries must not be null");
        this.getReservationUseCase =
                Objects.requireNonNull(getReservationUseCase, "getReservationUseCase must not be null");
    }

    @Override
    public void reserveStock(ReserveStockRequest request, StreamObserver<ReserveStockResponse> responseObserver) {
        try {
            ProductId productId = ProductId.of(UUID.fromString(request.getProductId()));
            UserId userId = UserId.of(UUID.fromString(request.getUserId()));
            String idempotencyKey = request.getIdempotencyKey();
            int quantity = request.getQuantity();

            ReserveStockCommand command = new ReserveStockCommand(idempotencyKey, userId, productId, quantity);
            ReserveStockResult result = reserveStockUseCase.execute(command);

            switch (result) {
                case ReserveStockResult.Reserved r -> sendReserveResponse(r.reservation(), responseObserver);
                case ReserveStockResult.IdempotentReplay r -> sendReserveResponse(r.reservation(), responseObserver);
                case ReserveStockResult.InsufficientStock s ->
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Requested " + s.requestedQuantity() + " units are not available")
                            .asRuntimeException());
                case ReserveStockResult.PurchaseLimitExceeded l ->
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Purchase limit of " + l.maxLimit() + " exceeded")
                            .asRuntimeException());
                case ReserveStockResult.ProductNotFound n ->
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Product not found")
                            .asRuntimeException());
                case ReserveStockResult.OptimisticConflict c ->
                    responseObserver.onError(Status.ABORTED
                            .withDescription("Optimistic locking conflict under contention")
                            .asRuntimeException());
                case ReserveStockResult.IdempotencyConflict c ->
                    responseObserver.onError(
                            Status.ALREADY_EXISTS.withDescription(c.message()).asRuntimeException());
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
    public void cancelReservation(
            CancelReservationRequest request, StreamObserver<CancelReservationResponse> responseObserver) {
        try {
            ReservationId reservationId = ReservationId.of(UUID.fromString(request.getReservationId()));
            CancelReservationResult result = cancelReservationUseCase.execute(reservationId);

            switch (result) {
                case CancelReservationResult.Cancelled c -> {
                    CancelReservationResponse response = CancelReservationResponse.newBuilder()
                            .setReservationId(c.reservation().id().value().toString())
                            .setStatus(c.reservation().status().name())
                            .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
                case CancelReservationResult.NotFound n ->
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Reservation " + n.id() + " not found")
                            .asRuntimeException());
                case CancelReservationResult.InvalidState s ->
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Cannot cancel reservation in status " + s.currentStatus())
                            .asRuntimeException());
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
    public void confirmReservation(
            ConfirmReservationRequest request, StreamObserver<ConfirmReservationResponse> responseObserver) {
        try {
            ReservationId reservationId = ReservationId.of(UUID.fromString(request.getReservationId()));
            ConfirmReservationResult result = confirmReservationUseCase.execute(reservationId);

            switch (result) {
                case ConfirmReservationResult.Confirmed c -> {
                    ConfirmReservationResponse response = ConfirmReservationResponse.newBuilder()
                            .setReservationId(c.reservation().id().value().toString())
                            .setStatus(c.reservation().status().name())
                            .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
                case ConfirmReservationResult.NotFound n ->
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Reservation " + n.reservationId() + " not found")
                            .asRuntimeException());
                case ConfirmReservationResult.InvalidState s ->
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Cannot confirm reservation in status " + s.currentStatus())
                            .asRuntimeException());
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
    public void getAvailability(
            GetAvailabilityRequest request, StreamObserver<GetAvailabilityResponse> responseObserver) {
        try {
            ProductId productId = ProductId.of(UUID.fromString(request.getProductId()));
            var inventory = productCatalogQueries.findInventory(productId);

            if (inventory.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Product " + productId.value() + " not found")
                        .asRuntimeException());
                return;
            }

            var inv = inventory.get();
            GetAvailabilityResponse response = GetAvailabilityResponse.newBuilder()
                    .setProductId(inv.productId().value().toString())
                    .setTotalQuantity(inv.totalQuantity())
                    .setAvailableQuantity(inv.availableQuantity())
                    .setReservedQuantity(inv.reservedQuantity())
                    .setSoldQuantity(inv.soldQuantity())
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
    public void getReservation(GetReservationRequest request, StreamObserver<GetReservationResponse> responseObserver) {
        try {
            ReservationId reservationId = ReservationId.of(UUID.fromString(request.getReservationId()));
            var found = getReservationUseCase.execute(reservationId);

            if (found.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Reservation " + reservationId.value() + " not found")
                        .asRuntimeException());
                return;
            }

            Reservation r = found.get();
            if (!request.getUserId().isBlank()) {
                UserId requestedUser = UserId.of(UUID.fromString(request.getUserId()));
                if (!r.userId().equals(requestedUser)) {
                    // Object-level authorization (spec §28): return NOT_FOUND for other users' resources
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Reservation " + reservationId.value() + " not found")
                            .asRuntimeException());
                    return;
                }
            }

            GetReservationResponse response = GetReservationResponse.newBuilder()
                    .setReservationId(r.id().value().toString())
                    .setUserId(r.userId().value().toString())
                    .setProductId(r.productId().value().toString())
                    .setQuantity(r.quantity())
                    .setStatus(r.status().name())
                    .setExpiresAt(r.expiresAt().toString())
                    .setCreatedAt(r.createdAt().toString())
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
    public void getProduct(GetProductRequest request, StreamObserver<GetProductResponse> responseObserver) {
        try {
            ProductId productId = ProductId.of(UUID.fromString(request.getProductId()));
            var found = productCatalogQueries.findProduct(productId);
            if (found.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Product " + productId.value() + " not found")
                        .asRuntimeException());
                return;
            }
            Product p = found.get();
            int avail = productCatalogQueries
                    .findInventory(productId)
                    .map(Inventory::availableQuantity)
                    .orElse(0);

            ProductDto dto = ProductDto.newBuilder()
                    .setId(p.id().value().toString())
                    .setSku(p.sku().value())
                    .setName(p.name())
                    .setDescription("")
                    .setPriceAmount(p.price().amount().toPlainString())
                    .setPriceCurrency(p.price().currency().getCurrencyCode())
                    .setAvailableQuantity(avail)
                    .build();

            responseObserver.onNext(
                    GetProductResponse.newBuilder().setProduct(dto).build());
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
    public void listProducts(ListProductsRequest request, StreamObserver<ListProductsResponse> responseObserver) {
        try {
            int limit = request.getLimit() <= 0 ? 20 : Math.min(request.getLimit(), 100);
            com.stockforge.inventory.application.ProductPage page;
            if (request.getCursor().isBlank()) {
                page = productCatalogQueries.firstPage(limit);
            } else {
                ProductId after = ProductId.of(UUID.fromString(request.getCursor()));
                page = productCatalogQueries.pageAfter(after, limit);
            }

            ListProductsResponse.Builder builder = ListProductsResponse.newBuilder();
            for (Product p : page.items()) {
                int avail = productCatalogQueries
                        .findInventory(p.id())
                        .map(Inventory::availableQuantity)
                        .orElse(0);
                builder.addProducts(ProductDto.newBuilder()
                        .setId(p.id().value().toString())
                        .setSku(p.sku().value())
                        .setName(p.name())
                        .setDescription("")
                        .setPriceAmount(p.price().amount().toPlainString())
                        .setPriceCurrency(p.price().currency().getCurrencyCode())
                        .setAvailableQuantity(avail)
                        .build());
            }
            if (page.nextAfter().isPresent()) {
                builder.setNextCursor(page.nextAfter().get().value().toString());
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

    private void sendReserveResponse(Reservation r, StreamObserver<ReserveStockResponse> responseObserver) {
        ReserveStockResponse response = ReserveStockResponse.newBuilder()
                .setReservationId(r.id().value().toString())
                .setStatus(r.status().name())
                .setExpiresAt(r.expiresAt().toString())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
