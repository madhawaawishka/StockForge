package com.stockforge.inventory.adapter.in.grpc;

import static com.stockforge.grpc.GrpcProblems.problem;

import com.stockforge.grpc.GrpcProblems;
import com.stockforge.inventory.adapter.in.TransientFailureRetry;
import com.stockforge.inventory.application.AdjustStockCommand;
import com.stockforge.inventory.application.AdjustStockResult;
import com.stockforge.inventory.application.AdjustStockUseCase;
import com.stockforge.inventory.application.Availability;
import com.stockforge.inventory.application.CancelReservationResult;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.CreateProductCommand;
import com.stockforge.inventory.application.CreateProductResult;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ProductPage;
import com.stockforge.inventory.application.ReserveStockCommand;
import com.stockforge.inventory.application.ReserveStockResult;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.Sku;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.v1.AdjustStockRequest;
import com.stockforge.inventory.v1.AdjustStockResponse;
import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.CancelReservationResponse;
import com.stockforge.inventory.v1.CreateProductRequest;
import com.stockforge.inventory.v1.CreateProductResponse;
import com.stockforge.inventory.v1.GetAvailabilityRequest;
import com.stockforge.inventory.v1.GetAvailabilityResponse;
import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetProductResponse;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.GetReservationResponse;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.ListProductsRequest;
import com.stockforge.inventory.v1.ListProductsResponse;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Inbound gRPC adapter, used by the API gateway (spec §12). It only translates: proto to commands, results to proto or
 * to a status plus problem type (see {@link GrpcProblems}). Unexpected exceptions are mapped by
 * {@link InventoryGrpcExceptionHandler}.
 */
@GrpcService
class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CreateProductUseCase createProduct;
    private final ProductCatalogQueries catalog;
    private final AdjustStockUseCase adjustStock;
    private final ReserveStockUseCase reserveStock;
    private final GetReservationUseCase getReservation;
    private final CancelReservationUseCase cancelReservation;
    private final TransientFailureRetry retry;

    InventoryGrpcService(
            CreateProductUseCase createProduct,
            ProductCatalogQueries catalog,
            AdjustStockUseCase adjustStock,
            ReserveStockUseCase reserveStock,
            GetReservationUseCase getReservation,
            CancelReservationUseCase cancelReservation,
            TransientFailureRetry retry) {
        this.createProduct = createProduct;
        this.catalog = catalog;
        this.adjustStock = adjustStock;
        this.reserveStock = reserveStock;
        this.getReservation = getReservation;
        this.cancelReservation = cancelReservation;
        this.retry = retry;
    }

    @Override
    public void createProduct(CreateProductRequest request, StreamObserver<CreateProductResponse> response) {
        CreateProductCommand command = new CreateProductCommand(
                Sku.of(request.getSku()),
                request.getName(),
                Money.of(request.getPriceAmount(), request.getPriceCurrency()),
                request.getInitialStock(),
                required(request.getActorId(), "actor_id"));
        switch (retry.execute(() -> createProduct.execute(command))) {
            case CreateProductResult.Created created ->
                complete(
                        response,
                        CreateProductResponse.newBuilder()
                                .setProduct(GrpcMapping.product(created.product()))
                                .setAvailability(GrpcMapping.availability(created.inventory(), false))
                                .build());
            case CreateProductResult.SkuAlreadyExists exists ->
                throw problem(
                        Status.ALREADY_EXISTS,
                        GrpcProblems.SKU_ALREADY_EXISTS,
                        "A product with SKU " + exists.sku() + " already exists");
        }
    }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<GetProductResponse> response) {
        ProductId id = productId(request.getProductId());
        Product product = catalog.findProduct(id).orElseThrow(() -> productNotFound(id));
        complete(
                response,
                GetProductResponse.newBuilder()
                        .setProduct(GrpcMapping.product(product))
                        .build());
    }

    @Override
    public void listProducts(ListProductsRequest request, StreamObserver<ListProductsResponse> response) {
        int limit = request.getLimit() == 0 ? DEFAULT_PAGE_SIZE : request.getLimit();
        if (limit < 1 || limit > ProductCatalogQueries.MAX_PAGE_SIZE) {
            throw problem(
                    Status.INVALID_ARGUMENT,
                    GrpcProblems.VALIDATION_FAILED,
                    "limit must be between 1 and " + ProductCatalogQueries.MAX_PAGE_SIZE);
        }
        ProductPage page = request.getCursor().isEmpty()
                ? catalog.firstPage(limit)
                : catalog.pageAfter(decodeCursor(request.getCursor()), limit);
        ListProductsResponse.Builder builder = ListProductsResponse.newBuilder();
        page.items().forEach(product -> builder.addProducts(GrpcMapping.product(product)));
        page.nextAfter().ifPresent(next -> builder.setNextCursor(encodeCursor(next)));
        complete(response, builder.build());
    }

    @Override
    public void getAvailability(GetAvailabilityRequest request, StreamObserver<GetAvailabilityResponse> response) {
        ProductId id = productId(request.getProductId());
        Availability availability = catalog.findAvailability(id).orElseThrow(() -> productNotFound(id));
        complete(
                response,
                GetAvailabilityResponse.newBuilder()
                        .setAvailability(GrpcMapping.availability(availability.inventory(), availability.approximate()))
                        .build());
    }

    @Override
    public void adjustStock(AdjustStockRequest request, StreamObserver<AdjustStockResponse> response) {
        AdjustStockCommand command = new AdjustStockCommand(
                request.getIdempotencyKey(),
                UserId.of(uuid(request.getActorId(), "actor_id")),
                productId(request.getProductId()),
                request.getDelta(),
                request.getReason());
        switch (retry.execute(() -> adjustStock.execute(command))) {
            case AdjustStockResult.Adjusted adjusted ->
                complete(
                        response,
                        AdjustStockResponse.newBuilder()
                                .setAvailability(GrpcMapping.availability(adjusted.inventory(), false))
                                .setIdempotentReplay(adjusted.replayed())
                                .build());
            case AdjustStockResult.ProductNotFound notFound -> throw productNotFound(notFound.productId());
            case AdjustStockResult.InsufficientAvailableStock insufficient ->
                throw problem(
                        Status.FAILED_PRECONDITION,
                        GrpcProblems.INSUFFICIENT_AVAILABLE_STOCK,
                        "Cannot write off " + -insufficient.delta()
                                + " units; reserved and sold units are untouchable");
            case AdjustStockResult.IdempotencyKeyReused reused -> throw idempotencyKeyReused();
        }
    }

    @Override
    public void reserveStock(ReserveStockRequest request, StreamObserver<ReserveStockResponse> response) {
        ReserveStockCommand command = new ReserveStockCommand(
                request.getIdempotencyKey(),
                UserId.of(uuid(request.getUserId(), "user_id")),
                productId(request.getProductId()),
                request.getQuantity());
        switch (retry.execute(() -> reserveStock.execute(command))) {
            case ReserveStockResult.Reserved reserved ->
                complete(
                        response,
                        ReserveStockResponse.newBuilder()
                                .setReservation(GrpcMapping.reservation(reserved.reservation()))
                                .setIdempotentReplay(reserved.replayed())
                                .build());
            case ReserveStockResult.InsufficientStock insufficient ->
                throw problem(
                        Status.FAILED_PRECONDITION,
                        GrpcProblems.INSUFFICIENT_STOCK,
                        "Requested " + insufficient.requestedQuantity() + " units are no longer available");
            case ReserveStockResult.PurchaseLimitExceeded exceeded ->
                throw problem(
                        Status.FAILED_PRECONDITION,
                        GrpcProblems.PURCHASE_LIMIT_EXCEEDED,
                        "At most " + exceeded.limit() + " units of this product per customer");
            case ReserveStockResult.ProductNotFound notFound -> throw productNotFound(notFound.productId());
            case ReserveStockResult.OptimisticConflict conflict ->
                throw problem(
                        Status.ABORTED,
                        GrpcProblems.OPTIMISTIC_LOCK_CONFLICT,
                        "Too much contention on this product; retry with the same idempotency key");
            case ReserveStockResult.IdempotencyKeyReused reused -> throw idempotencyKeyReused();
        }
    }

    @Override
    public void getReservation(GetReservationRequest request, StreamObserver<GetReservationResponse> response) {
        ReservationId id = ReservationId.of(uuid(request.getReservationId(), "reservation_id"));
        var reservation = getReservation
                .execute(id, requester(request.getRequesterUserId()))
                .orElseThrow(() -> reservationNotFound(id));
        complete(
                response,
                GetReservationResponse.newBuilder()
                        .setReservation(GrpcMapping.reservation(reservation))
                        .build());
    }

    @Override
    public void cancelReservation(
            CancelReservationRequest request, StreamObserver<CancelReservationResponse> response) {
        ReservationId id = ReservationId.of(uuid(request.getReservationId(), "reservation_id"));
        Optional<UserId> requester = requester(request.getRequesterUserId());
        switch (retry.execute(() -> cancelReservation.execute(id, requester))) {
            case CancelReservationResult.Cancelled cancelled ->
                complete(
                        response,
                        CancelReservationResponse.newBuilder()
                                .setReservation(GrpcMapping.reservation(cancelled.reservation()))
                                .build());
            case CancelReservationResult.NotFound notFound -> throw reservationNotFound(notFound.id());
            case CancelReservationResult.InvalidState invalid ->
                throw problem(
                        Status.FAILED_PRECONDITION,
                        GrpcProblems.INVALID_RESERVATION_STATE,
                        "Reservation is " + invalid.currentStatus() + "; only PENDING reservations can be cancelled");
        }
    }

    private static <T> void complete(StreamObserver<T> observer, T message) {
        observer.onNext(message);
        observer.onCompleted();
    }

    private static Optional<UserId> requester(String userId) {
        return userId.isEmpty() ? Optional.empty() : Optional.of(UserId.of(uuid(userId, "requester_user_id")));
    }

    private static ProductId productId(String value) {
        return ProductId.of(uuid(value, "product_id"));
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field));
        } catch (IllegalArgumentException malformed) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, field + " must be a UUID");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, field + " is required");
        }
        return value;
    }

    private static String encodeCursor(ProductId lastSeen) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(lastSeen.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ProductId decodeCursor(String cursor) {
        try {
            return ProductId.of(
                    UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException malformed) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, "cursor is not valid");
        }
    }

    private static RuntimeException productNotFound(ProductId id) {
        return problem(Status.NOT_FOUND, GrpcProblems.PRODUCT_NOT_FOUND, "Product " + id + " does not exist");
    }

    private static RuntimeException reservationNotFound(ReservationId id) {
        return problem(Status.NOT_FOUND, GrpcProblems.RESERVATION_NOT_FOUND, "Reservation " + id + " does not exist");
    }

    private static RuntimeException idempotencyKeyReused() {
        return problem(
                Status.INVALID_ARGUMENT,
                GrpcProblems.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for a different request");
    }

    /** Proto representations of the domain. */
    static final class GrpcMapping {

        private GrpcMapping() {}

        static com.stockforge.inventory.v1.Product product(Product product) {
            return com.stockforge.inventory.v1.Product.newBuilder()
                    .setId(product.id().toString())
                    .setSku(product.sku().value())
                    .setName(product.name())
                    .setPriceAmount(product.price().amount().toPlainString())
                    .setPriceCurrency(product.price().currency().getCurrencyCode())
                    .setStatus(product.status().name())
                    .setCreatedAt(product.createdAt().toString())
                    .setUpdatedAt(product.updatedAt().toString())
                    .build();
        }

        static com.stockforge.inventory.v1.Availability availability(Inventory inventory, boolean approximate) {
            return com.stockforge.inventory.v1.Availability.newBuilder()
                    .setProductId(inventory.productId().toString())
                    .setTotalQuantity(inventory.totalQuantity())
                    .setAvailableQuantity(inventory.availableQuantity())
                    .setReservedQuantity(inventory.reservedQuantity())
                    .setSoldQuantity(inventory.soldQuantity())
                    .setUpdatedAt(inventory.updatedAt().toString())
                    .setApproximate(approximate)
                    .build();
        }

        static com.stockforge.inventory.v1.Reservation reservation(
                com.stockforge.inventory.domain.model.Reservation reservation) {
            return com.stockforge.inventory.v1.Reservation.newBuilder()
                    .setId(reservation.id().toString())
                    .setUserId(reservation.userId().toString())
                    .setProductId(reservation.productId().toString())
                    .setQuantity(reservation.quantity())
                    .setStatus(reservation.status().name())
                    .setExpiresAt(reservation.expiresAt().toString())
                    .setCreatedAt(reservation.createdAt().toString())
                    .setUpdatedAt(reservation.updatedAt().toString())
                    .build();
        }
    }
}
