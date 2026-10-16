package com.stockforge.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.gateway.domain.model.Caller;
import com.stockforge.gateway.domain.model.ServiceProblem;
import com.stockforge.gateway.domain.model.Views;
import com.stockforge.gateway.domain.port.InventoryApi;
import com.stockforge.gateway.domain.port.OrderApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayUseCasesTest {

    private final RecordingApis apis = new RecordingApis();
    private final Caller shopper = new Caller(UUID.randomUUID(), false);
    private final Caller admin = new Caller(UUID.randomUUID(), true);

    @Test
    void ownershipScopeIsTheCallerForShoppersAndUnrestrictedForAdmins() {
        StorefrontUseCases storefront = new StorefrontUseCases(apis, apis, false);

        storefront.reservation(shopper, "r-1");
        storefront.reservation(admin, "r-1");

        assertThat(apis.requesterScopes).containsExactly(shopper.userId().toString(), "");
    }

    @Test
    void paymentSimulationIsRejectedWhereItIsDisabled() {
        StorefrontUseCases storefront = new StorefrontUseCases(apis, apis, false);

        assertThatThrownBy(() -> storefront.placeOrder(shopper, "key", "r-1", Optional.of("FAILED")))
                .isInstanceOfSatisfying(
                        ServiceProblem.class,
                        problem -> assertThat(problem.type()).isEqualTo("payment-simulation-disabled"));
        assertThat(apis.placedOrders).isEmpty();
    }

    @Test
    void paymentSimulationMustBeAKnownOutcome() {
        StorefrontUseCases storefront = new StorefrontUseCases(apis, apis, true);

        assertThatThrownBy(() -> storefront.placeOrder(shopper, "key", "r-1", Optional.of("MAYBE")))
                .isInstanceOf(ServiceProblem.class);
        storefront.placeOrder(shopper, "key", "r-1", Optional.of("TIMED_OUT"));
        assertThat(apis.placedOrders).containsExactly("TIMED_OUT");
    }

    @Test
    void adminOperationsRequireTheAdminRoleEvenIfRoutingLetARequestThrough() {
        AdminUseCases adminUseCases = new AdminUseCases(apis, apis);

        assertThatThrownBy(() -> adminUseCases.replayDeadLetters(shopper, "payment-events", 10))
                .isInstanceOfSatisfying(
                        ServiceProblem.class,
                        problem -> assertThat(problem.kind()).isEqualTo(ServiceProblem.Kind.FORBIDDEN));
        assertThat(adminUseCases.replayDeadLetters(admin, "payment-events", 10)).isEqualTo(7);
    }

    @Test
    void pageSizesAreBounded() {
        StorefrontUseCases storefront = new StorefrontUseCases(apis, apis, false);

        assertThatThrownBy(() -> storefront.products(101, Optional.empty())).isInstanceOf(ServiceProblem.class);
        assertThatThrownBy(() -> storefront.orders(shopper, 0, Optional.empty()))
                .isInstanceOf(ServiceProblem.class);
    }

    /** Records what the use cases asked the services; returns canned data. */
    private static final class RecordingApis implements InventoryApi, OrderApi {
        final List<String> requesterScopes = new ArrayList<>();
        final List<String> placedOrders = new ArrayList<>();

        private static final Views.Reservation RESERVATION =
                new Views.Reservation("r-1", "u", "p", 1, "PENDING", "t", "t", "t");
        private static final Views.Order ORDER =
                new Views.Order("o-1", "u", "r-1", "PENDING_PAYMENT", "1.00", "USD", List.of(), List.of(), "t", "t");

        @Override
        public Views.ProductPage listProducts(int limit, Optional<String> cursor) {
            return new Views.ProductPage(List.of(), Optional.empty());
        }

        @Override
        public Views.Product getProduct(String productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Views.Availability getAvailability(String productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Views.Idempotent<Views.Reservation> reserve(
                String idempotencyKey, UUID userId, String productId, int quantity) {
            return new Views.Idempotent<>(RESERVATION, false);
        }

        @Override
        public Views.Reservation getReservation(String reservationId, String requesterScope) {
            requesterScopes.add(requesterScope);
            return RESERVATION;
        }

        @Override
        public Views.Reservation cancelReservation(String reservationId, String requesterScope) {
            return RESERVATION;
        }

        @Override
        public Views.Product createProduct(
                String sku, String name, String priceAmount, String priceCurrency, int initialStock, UUID actorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Views.Idempotent<Views.Availability> adjustStock(
                String idempotencyKey, String productId, int delta, String reason, UUID actorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Views.Idempotent<Views.Order> placeOrder(
                String idempotencyKey, UUID userId, String reservationId, Optional<String> paymentSimulation) {
            placedOrders.add(paymentSimulation.orElse("none"));
            return new Views.Idempotent<>(ORDER, false);
        }

        @Override
        public Views.Order getOrder(String orderId, String requesterScope) {
            return ORDER;
        }

        @Override
        public Views.OrderPage listOrders(UUID userId, int limit, Optional<String> cursor) {
            return new Views.OrderPage(List.of(), Optional.empty());
        }

        @Override
        public int replayDeadLetters(String topic, int maxMessages, UUID actorId) {
            return 7;
        }
    }
}
