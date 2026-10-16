package com.stockforge.order.testsupport;

import com.stockforge.inventory.v1.GetProductRequest;
import com.stockforge.inventory.v1.GetProductResponse;
import com.stockforge.inventory.v1.GetReservationRequest;
import com.stockforge.inventory.v1.GetReservationResponse;
import com.stockforge.inventory.v1.InventoryServiceGrpc;
import com.stockforge.inventory.v1.Product;
import com.stockforge.inventory.v1.Reservation;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Plays the Inventory service's gRPC API for order integration tests, with the same owner check and NOT_FOUND
 * semantics. Tests register the reservations they need.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeInventory {

    @Bean
    Service fakeInventoryService() {
        return new Service();
    }

    public static class Service extends InventoryServiceGrpc.InventoryServiceImplBase {

        private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
        private final Map<String, Product> products = new ConcurrentHashMap<>();

        /** Registers a PENDING reservation of {@code quantity} units at {@code price} USD; returns its id. */
        public String pendingReservation(UUID owner, int quantity, String price) {
            String reservationId = UUID.randomUUID().toString();
            String productId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            reservations.put(
                    reservationId,
                    Reservation.newBuilder()
                            .setId(reservationId)
                            .setUserId(owner.toString())
                            .setProductId(productId)
                            .setQuantity(quantity)
                            .setStatus("PENDING")
                            .setExpiresAt(now.plusSeconds(600).toString())
                            .setCreatedAt(now.toString())
                            .setUpdatedAt(now.toString())
                            .build());
            products.put(
                    productId,
                    Product.newBuilder()
                            .setId(productId)
                            .setSku("SKU-" + productId.substring(0, 8).toUpperCase(java.util.Locale.ROOT))
                            .setName("Test product")
                            .setPriceAmount(price)
                            .setPriceCurrency("USD")
                            .setStatus("ACTIVE")
                            .build());
            return reservationId;
        }

        @Override
        public void getReservation(GetReservationRequest request, StreamObserver<GetReservationResponse> response) {
            Reservation reservation = reservations.get(request.getReservationId());
            if (reservation == null
                    || (!request.getRequesterUserId().isEmpty()
                            && !reservation.getUserId().equals(request.getRequesterUserId()))) {
                response.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            response.onNext(GetReservationResponse.newBuilder()
                    .setReservation(reservation)
                    .build());
            response.onCompleted();
        }

        @Override
        public void getProduct(GetProductRequest request, StreamObserver<GetProductResponse> response) {
            Product product = products.get(request.getProductId());
            if (product == null) {
                response.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            response.onNext(GetProductResponse.newBuilder().setProduct(product).build());
            response.onCompleted();
        }
    }
}
