package com.stockforge.order.adapter.out.grpc;

import com.stockforge.inventory.v1.CancelReservationRequest;
import com.stockforge.inventory.v1.ConfirmReservationRequest;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.port.InventoryClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class GrpcInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcInventoryClient.class);
    private static final long DEADLINE_SECONDS = 2;

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    GrpcInventoryClient(InventoryServiceGrpc.InventoryServiceBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
    }

    private InventoryServiceGrpc.InventoryServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public ConfirmResult confirmReservation(ReservationId reservationId) {
        try {
            ConfirmReservationRequest request = ConfirmReservationRequest.newBuilder()
                    .setReservationId(reservationId.value().toString())
                    .build();
            withDeadline().confirmReservation(request);
            return ConfirmResult.CONFIRMED;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND
                    || e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                log.warn("Reservation {} could not be confirmed (status: {})", reservationId, e.getStatus());
                return ConfirmResult.EXPIRED_OR_INVALID;
            }
            log.error("gRPC error calling confirmReservation for {}: {}", reservationId, e.getMessage());
            return ConfirmResult.FAILED;
        }
    }

    @Override
    public void releaseReservation(ReservationId reservationId, String reason) {
        try {
            CancelReservationRequest request = CancelReservationRequest.newBuilder()
                    .setReservationId(reservationId.value().toString())
                    .build();
            withDeadline().cancelReservation(request);
        } catch (Exception e) {
            log.error("Failed to release reservation {} in inventory: {}", reservationId, e.getMessage());
        }
    }
}
