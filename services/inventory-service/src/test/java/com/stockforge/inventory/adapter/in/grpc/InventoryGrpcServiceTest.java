package com.stockforge.inventory.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.ReserveStockRequest;
import com.stockforge.inventory.v1.ReserveStockResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryGrpcServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));

    private ReserveStockUseCase reserveStockUseCase;
    private CancelReservationUseCase cancelReservationUseCase;
    private ConfirmReservationUseCase confirmReservationUseCase;
    private ProductCatalogQueries productCatalogQueries;
    private GetReservationUseCase getReservationUseCase;
    private InventoryGrpcService grpcService;

    @BeforeEach
    void setUp() {
        reserveStockUseCase = mock(ReserveStockUseCase.class);
        cancelReservationUseCase = mock(CancelReservationUseCase.class);
        confirmReservationUseCase = mock(ConfirmReservationUseCase.class);
        productCatalogQueries = mock(ProductCatalogQueries.class);
        getReservationUseCase = mock(GetReservationUseCase.class);

        grpcService = new InventoryGrpcService(
                reserveStockUseCase,
                cancelReservationUseCase,
                confirmReservationUseCase,
                productCatalogQueries,
                getReservationUseCase);
    }

    @Test
    void reserveStockSuccess() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        when(reserveStockUseCase.execute(any(ReserveStockCommand.class)))
                .thenReturn(new ReserveStockResult.Reserved(reservation));

        ReserveStockRequest request = ReserveStockRequest.newBuilder()
                .setProductId(PRODUCT_ID.value().toString())
                .setUserId(USER_ID.value().toString())
                .setQuantity(2)
                .setIdempotencyKey("idemp-1")
                .build();

        AtomicReference<ReserveStockResponse> responseRef = new AtomicReference<>();
        grpcService.reserveStock(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(ReserveStockResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getReservationId())
                .isEqualTo(RESERVATION_ID.value().toString());
        assertThat(responseRef.get().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void reserveStockInsufficientStockReturnsFailedPrecondition() {
        when(reserveStockUseCase.execute(any(ReserveStockCommand.class)))
                .thenReturn(new ReserveStockResult.InsufficientStock(PRODUCT_ID, 5));

        ReserveStockRequest request = ReserveStockRequest.newBuilder()
                .setProductId(PRODUCT_ID.value().toString())
                .setUserId(USER_ID.value().toString())
                .setQuantity(5)
                .setIdempotencyKey("idemp-2")
                .build();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        grpcService.reserveStock(request, new SimpleStreamObserver<>() {
            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
            }
        });

        assertThat(errorRef.get()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException ex = (StatusRuntimeException) errorRef.get();
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void cancelReservationSuccess() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        when(cancelReservationUseCase.execute(RESERVATION_ID))
                .thenReturn(new CancelReservationResult.Cancelled(reservation.cancel(NOW)));

        CancelReservationRequest request = CancelReservationRequest.newBuilder()
                .setReservationId(RESERVATION_ID.value().toString())
                .setUserId(USER_ID.value().toString())
                .build();

        AtomicReference<CancelReservationResponse> responseRef = new AtomicReference<>();
        grpcService.cancelReservation(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(CancelReservationResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void confirmReservationSuccess() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        when(confirmReservationUseCase.execute(RESERVATION_ID))
                .thenReturn(new ConfirmReservationResult.Confirmed(reservation.confirm(NOW)));

        ConfirmReservationRequest request = ConfirmReservationRequest.newBuilder()
                .setReservationId(RESERVATION_ID.value().toString())
                .build();

        AtomicReference<ConfirmReservationResponse> responseRef = new AtomicReference<>();
        grpcService.confirmReservation(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(ConfirmReservationResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void getAvailabilitySuccess() {
        Inventory inv = Inventory.initial(PRODUCT_ID, 10, NOW).reserve(2, NOW);
        when(productCatalogQueries.findInventory(PRODUCT_ID)).thenReturn(Optional.of(inv));

        GetAvailabilityRequest request = GetAvailabilityRequest.newBuilder()
                .setProductId(PRODUCT_ID.value().toString())
                .build();

        AtomicReference<GetAvailabilityResponse> responseRef = new AtomicReference<>();
        grpcService.getAvailability(request, new SimpleStreamObserver<>() {
            @Override
            public void onNext(GetAvailabilityResponse value) {
                responseRef.set(value);
            }
        });

        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getAvailableQuantity()).isEqualTo(8);
        assertThat(responseRef.get().getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void getReservationEnforcesObjectLevelAuthorization() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, NOW.plusSeconds(600), NOW);
        when(getReservationUseCase.execute(RESERVATION_ID)).thenReturn(Optional.of(reservation));

        UserId otherUser = UserId.of(UUID.fromString("0192f5c4-9999-7000-8000-000000000000"));
        GetReservationRequest request = GetReservationRequest.newBuilder()
                .setReservationId(RESERVATION_ID.value().toString())
                .setUserId(otherUser.value().toString())
                .build();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        grpcService.getReservation(request, new SimpleStreamObserver<>() {
            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
            }
        });

        assertThat(errorRef.get()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException ex = (StatusRuntimeException) errorRef.get();
        // BOLA defense (§28): returns NOT_FOUND rather than forbidden to prevent probing
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
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
