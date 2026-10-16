package com.stockforge.inventory.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.grpc.GrpcProblems;
import com.stockforge.inventory.adapter.in.TransientFailureRetry;
import com.stockforge.inventory.application.AdjustStockUseCase;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.testsupport.InMemoryProductCache;
import com.stockforge.inventory.testsupport.InventoryFixture;
import com.stockforge.inventory.v1.AdjustStockRequest;
import com.stockforge.inventory.v1.AdjustStockResponse;
import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.CancelReservationResponse;
import com.stockforge.inventory.v1.CreateProductRequest;
import com.stockforge.inventory.v1.CreateProductResponse;
import com.stockforge.inventory.v1.GetAvailabilityRequest;
import com.stockforge.inventory.v1.GetAvailabilityResponse;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.GetReservationResponse;
import com.stockforge.inventory.v1.ListProductsRequest;
import com.stockforge.inventory.v1.ListProductsResponse;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gRPC contract of the inventory service: business outcomes map to the status codes of spec §12 plus the problem
 * type trailer the gateway relies on. Runs real use cases on in-memory adapters.
 */
class InventoryGrpcServiceTest {

    private static final String ALICE = "0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f";
    private static final String MALLORY = "0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e99";
    private static final String ADMIN = "0192f5c4-0000-7000-8000-0000000000ad";

    private InventoryFixture fixture;
    private InventoryGrpcService service;
    private ProductId product;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture();
        InMemoryProductCache cache = new InMemoryProductCache();
        service = new InventoryGrpcService(
                new CreateProductUseCase(
                        fixture.products, fixture.inventories, fixture.auditLog, fixture.ids, fixture.clock),
                new ProductCatalogQueries(fixture.products, fixture.inventories, cache),
                new AdjustStockUseCase(
                        fixture.inventories,
                        fixture.idempotencyKeys,
                        fixture.auditLog,
                        cache,
                        fixture.clock,
                        InventoryFixture.POLICY),
                new ReserveStockUseCase(
                        fixture.products,
                        fixture.strategy,
                        fixture.reservations,
                        fixture.idempotencyKeys,
                        fixture.purchaseLimits,
                        fixture.outbox,
                        fixture.ids,
                        fixture.clock,
                        InventoryFixture.POLICY),
                new GetReservationUseCase(fixture.reservations),
                new CancelReservationUseCase(
                        fixture.reservations,
                        fixture.inventories,
                        fixture.purchaseLimits,
                        fixture.outbox,
                        fixture.clock),
                new TransientFailureRetry(3));
        product = fixture.product("SNEAKER-1", 2);
    }

    @Test
    void reservesAndFlagsAReplay() {
        ReserveStockResponse first = InventoryGrpcServiceTest.<ReserveStockResponse>call(
                observer -> service.reserveStock(reserve("key-1", 1), observer));
        ReserveStockResponse replay = InventoryGrpcServiceTest.<ReserveStockResponse>call(
                observer -> service.reserveStock(reserve("key-1", 1), observer));

        assertThat(first.getReservation().getStatus()).isEqualTo("PENDING");
        assertThat(first.getReservation().getUserId()).isEqualTo(ALICE);
        assertThat(first.getIdempotentReplay()).isFalse();
        assertThat(replay.getReservation().getId())
                .isEqualTo(first.getReservation().getId());
        assertThat(replay.getIdempotentReplay()).isTrue();
    }

    @Test
    void soldOutIsFailedPreconditionWithTheInsufficientStockProblemType() {
        assertProblem(
                () -> InventoryGrpcServiceTest.<ReserveStockResponse>call(
                        observer -> service.reserveStock(reserve("key-1", 3), observer)),
                Status.Code.FAILED_PRECONDITION,
                GrpcProblems.PURCHASE_LIMIT_EXCEEDED);
        InventoryGrpcServiceTest.<ReserveStockResponse>call(
                observer -> service.reserveStock(reserve("key-2", 2), observer));

        assertProblem(
                () -> InventoryGrpcServiceTest.<ReserveStockResponse>call(observer -> service.reserveStock(
                        reserve("key-3", 1).toBuilder().setUserId(MALLORY).build(), observer)),
                Status.Code.FAILED_PRECONDITION,
                GrpcProblems.INSUFFICIENT_STOCK);
    }

    @Test
    void malformedIdsAreInvalidArgument() {
        assertProblem(
                () -> InventoryGrpcServiceTest.<ReserveStockResponse>call(observer -> service.reserveStock(
                        reserve("key-1", 1).toBuilder()
                                .setProductId("not-a-uuid")
                                .build(),
                        observer)),
                Status.Code.INVALID_ARGUMENT,
                GrpcProblems.VALIDATION_FAILED);
    }

    @Test
    void anotherUsersReservationIsNotFoundButAPrivilegedCallerSeesIt() {
        String id = InventoryGrpcServiceTest.<ReserveStockResponse>call(
                        observer -> service.reserveStock(reserve("key-1", 1), observer))
                .getReservation()
                .getId();

        assertProblem(
                () -> InventoryGrpcServiceTest.<GetReservationResponse>call(observer -> service.getReservation(
                        GetReservationRequest.newBuilder()
                                .setReservationId(id)
                                .setRequesterUserId(MALLORY)
                                .build(),
                        observer)),
                Status.Code.NOT_FOUND,
                GrpcProblems.RESERVATION_NOT_FOUND);
        GetReservationResponse privileged =
                InventoryGrpcServiceTest.<GetReservationResponse>call(observer -> service.getReservation(
                        GetReservationRequest.newBuilder().setReservationId(id).build(), observer));
        assertThat(privileged.getReservation().getId()).isEqualTo(id);
    }

    @Test
    void cancelsOwnReservationOnlyOnce() {
        String id = InventoryGrpcServiceTest.<ReserveStockResponse>call(
                        observer -> service.reserveStock(reserve("key-1", 1), observer))
                .getReservation()
                .getId();
        CancelReservationRequest cancel = CancelReservationRequest.newBuilder()
                .setReservationId(id)
                .setRequesterUserId(ALICE)
                .build();

        CancelReservationResponse cancelled = InventoryGrpcServiceTest.<CancelReservationResponse>call(
                observer -> service.cancelReservation(cancel, observer));

        assertThat(cancelled.getReservation().getStatus()).isEqualTo("CANCELLED");
        assertProblem(
                () -> InventoryGrpcServiceTest.<CancelReservationResponse>call(
                        observer -> service.cancelReservation(cancel, observer)),
                Status.Code.FAILED_PRECONDITION,
                GrpcProblems.INVALID_RESERVATION_STATE);
    }

    @Test
    void availabilityIsApproximateWhenServedFromTheCache() {
        GetAvailabilityRequest request = GetAvailabilityRequest.newBuilder()
                .setProductId(product.toString())
                .build();

        GetAvailabilityResponse fromDatabase = InventoryGrpcServiceTest.<GetAvailabilityResponse>call(
                observer -> service.getAvailability(request, observer));
        GetAvailabilityResponse fromCache = InventoryGrpcServiceTest.<GetAvailabilityResponse>call(
                observer -> service.getAvailability(request, observer));

        assertThat(fromDatabase.getAvailability().getAvailableQuantity()).isEqualTo(2);
        assertThat(fromDatabase.getAvailability().getApproximate()).isFalse();
        assertThat(fromCache.getAvailability().getApproximate()).isTrue();
    }

    @Test
    void adminCreatesProductsAndAdjustsStock() {
        CreateProductResponse created =
                InventoryGrpcServiceTest.<CreateProductResponse>call(observer -> service.createProduct(
                        CreateProductRequest.newBuilder()
                                .setSku("NEW-SKU-1")
                                .setName("New product")
                                .setPriceAmount("10.00")
                                .setPriceCurrency("USD")
                                .setInitialStock(5)
                                .setActorId(ADMIN)
                                .build(),
                        observer));
        AdjustStockResponse adjusted =
                InventoryGrpcServiceTest.<AdjustStockResponse>call(observer -> service.adjustStock(
                        AdjustStockRequest.newBuilder()
                                .setProductId(created.getProduct().getId())
                                .setDelta(-2)
                                .setReason("Damaged in storage")
                                .setActorId(ADMIN)
                                .setIdempotencyKey("adj-1")
                                .build(),
                        observer));

        assertThat(created.getAvailability().getTotalQuantity()).isEqualTo(5);
        assertThat(adjusted.getAvailability().getTotalQuantity()).isEqualTo(3);
        assertThat(fixture.auditLog.entries())
                .extracting(entry -> entry.action())
                .containsExactly("PRODUCT_CREATED", "STOCK_ADJUSTED");
    }

    @Test
    void duplicateSkuIsAlreadyExists() {
        CreateProductRequest request = CreateProductRequest.newBuilder()
                .setSku("SNEAKER-1")
                .setName("Duplicate")
                .setPriceAmount("10.00")
                .setPriceCurrency("USD")
                .setInitialStock(1)
                .setActorId(ADMIN)
                .build();

        assertProblem(
                () -> InventoryGrpcServiceTest.<CreateProductResponse>call(
                        observer -> service.createProduct(request, observer)),
                Status.Code.ALREADY_EXISTS,
                GrpcProblems.SKU_ALREADY_EXISTS);
    }

    @Test
    void listsProductsWithAKeysetCursor() {
        fixture.product("SNEAKER-2", 1);
        fixture.product("SNEAKER-3", 1);

        ListProductsResponse first =
                InventoryGrpcServiceTest.<ListProductsResponse>call(observer -> service.listProducts(
                        ListProductsRequest.newBuilder().setLimit(2).build(), observer));
        ListProductsResponse second =
                InventoryGrpcServiceTest.<ListProductsResponse>call(observer -> service.listProducts(
                        ListProductsRequest.newBuilder()
                                .setLimit(2)
                                .setCursor(first.getNextCursor())
                                .build(),
                        observer));

        assertThat(first.getProductsList()).extracting(p -> p.getSku()).containsExactly("SNEAKER-1", "SNEAKER-2");
        assertThat(second.getProductsList()).extracting(p -> p.getSku()).containsExactly("SNEAKER-3");
        assertThat(second.getNextCursor()).isEmpty();
    }

    private ReserveStockRequest reserve(String key, int quantity) {
        return ReserveStockRequest.newBuilder()
                .setProductId(product.toString())
                .setUserId(ALICE)
                .setQuantity(quantity)
                .setIdempotencyKey(key)
                .build();
    }

    private static void assertProblem(Runnable call, Status.Code code, String problemType) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class, error -> {
            assertThat(error.getStatus().getCode()).isEqualTo(code);
            assertThat(GrpcProblems.problemTypeOf(error)).isEqualTo(problemType);
        });
    }

    /** Runs a unary call and returns its single response. */
    private static <T> T call(java.util.function.Consumer<StreamObserver<T>> invocation) {
        List<T> responses = new ArrayList<>();
        invocation.accept(new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError("unexpected onError", error);
            }

            @Override
            public void onCompleted() {}
        });
        assertThat(responses).hasSize(1);
        return responses.getFirst();
    }

    @Test
    void unknownIdsAreNotFound() {
        assertProblem(
                () -> InventoryGrpcServiceTest.<GetAvailabilityResponse>call(observer -> service.getAvailability(
                        GetAvailabilityRequest.newBuilder()
                                .setProductId(UUID.randomUUID().toString())
                                .build(),
                        observer)),
                Status.Code.NOT_FOUND,
                GrpcProblems.PRODUCT_NOT_FOUND);
    }
}
