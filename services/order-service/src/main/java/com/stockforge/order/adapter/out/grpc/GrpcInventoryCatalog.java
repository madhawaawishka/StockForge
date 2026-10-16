package com.stockforge.order.adapter.out.grpc;

import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.Reservation;
import com.stockforge.order.config.OrderProperties;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.InventoryCatalog;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC client of the Inventory service (spec §12, §23). Every call has a deadline, runs through the {@code inventory}
 * circuit breaker, and asks as the order's user, so Inventory enforces object-level authorization as well.
 */
@Component
class GrpcInventoryCatalog implements InventoryCatalog {

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final CircuitBreaker breaker;
    private final Duration deadline;

    GrpcInventoryCatalog(GrpcChannelFactory channels, CircuitBreakerRegistry breakers, OrderProperties properties) {
        this.stub = InventoryServiceGrpc.newBlockingStub(channels.createChannel("inventory"));
        this.breaker = breakers.circuitBreaker("inventory");
        this.deadline = properties.inventory().deadline();
    }

    @Override
    public Optional<ReservationSnapshot> findReservation(ReservationId id, UserId owner) {
        return call(() -> {
            Reservation reservation = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getReservation(GetReservationRequest.newBuilder()
                            .setReservationId(id.toString())
                            .setRequesterUserId(owner.toString())
                            .build())
                    .getReservation();
            return new ReservationSnapshot(
                    ReservationId.of(UUID.fromString(reservation.getId())),
                    UserId.of(UUID.fromString(reservation.getUserId())),
                    ProductId.of(UUID.fromString(reservation.getProductId())),
                    reservation.getQuantity(),
                    reservation.getStatus(),
                    Instant.parse(reservation.getExpiresAt()));
        });
    }

    @Override
    public Optional<Money> findPrice(ProductId productId) {
        return call(() -> {
            var product = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getProduct(GetProductRequest.newBuilder()
                            .setProductId(productId.toString())
                            .build())
                    .getProduct();
            return Money.of(product.getPriceAmount(), product.getPriceCurrency());
        });
    }

    /** NOT_FOUND is an answer, not a failure: it completes successfully and does not trip the circuit breaker. */
    private <T> Optional<T> call(Supplier<T> request) {
        try {
            return breaker.executeSupplier(() -> {
                try {
                    return Optional.of(request.get());
                } catch (StatusRuntimeException error) {
                    if (error.getStatus().getCode() == Status.Code.NOT_FOUND) {
                        return Optional.<T>empty();
                    }
                    throw error;
                }
            });
        } catch (CallNotPermittedException open) {
            throw new InventoryUnavailableException("Inventory circuit breaker is open", open);
        } catch (StatusRuntimeException failure) {
            throw new InventoryUnavailableException(
                    "Inventory call failed: " + failure.getStatus().getCode(), failure);
        }
    }
}
