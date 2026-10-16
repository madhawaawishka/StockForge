package com.stockforge.gateway.application;

import com.stockforge.gateway.domain.model.Caller;
import com.stockforge.gateway.domain.model.ServiceProblem;
import com.stockforge.gateway.domain.model.Views;
import com.stockforge.gateway.domain.port.InventoryApi;
import com.stockforge.gateway.domain.port.OrderApi;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a shopper can do through the public API (spec §12). The gateway owns authentication, request validation and
 * routing (spec §10); business rules and object-level authorization are enforced again by the owning services, which
 * receive the caller's id from the token, never from the client (defense in depth, spec §28).
 */
public class StorefrontUseCases {

    public static final int MAX_PAGE_SIZE = 100;
    public static final Set<String> PAYMENT_SIMULATIONS = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "NO_RESPONSE");

    private final InventoryApi inventory;
    private final OrderApi orders;
    private final boolean paymentSimulationAllowed;

    /** @param paymentSimulationAllowed whether clients may choose the simulated payment outcome (FR-6, non-prod) */
    public StorefrontUseCases(InventoryApi inventory, OrderApi orders, boolean paymentSimulationAllowed) {
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.paymentSimulationAllowed = paymentSimulationAllowed;
    }

    public Views.ProductPage products(int limit, Optional<String> cursor) {
        requirePageSize(limit);
        return inventory.listProducts(limit, cursor);
    }

    public Views.Product product(String productId) {
        return inventory.getProduct(productId);
    }

    public Views.Availability availability(String productId) {
        return inventory.getAvailability(productId);
    }

    public Views.Idempotent<Views.Reservation> reserve(
            Caller caller, String idempotencyKey, String productId, int quantity) {
        return inventory.reserve(idempotencyKey, caller.userId(), productId, quantity);
    }

    public Views.Reservation reservation(Caller caller, String reservationId) {
        return inventory.getReservation(reservationId, caller.requesterScope());
    }

    public Views.Reservation cancelReservation(Caller caller, String reservationId) {
        // Even an administrator cancels only as the owner would: the cancellation is the shopper's decision.
        return inventory.cancelReservation(reservationId, caller.userId().toString());
    }

    public Views.Idempotent<Views.Order> placeOrder(
            Caller caller, String idempotencyKey, String reservationId, Optional<String> paymentSimulation) {
        if (paymentSimulation.isPresent()) {
            if (!paymentSimulationAllowed) {
                throw new ServiceProblem(
                        "payment-simulation-disabled",
                        ServiceProblem.Kind.INVALID,
                        "Choosing the payment outcome is only possible in test environments",
                        null);
            }
            if (!PAYMENT_SIMULATIONS.contains(paymentSimulation.get())) {
                throw new ServiceProblem(
                        "validation-failed",
                        ServiceProblem.Kind.INVALID,
                        "X-Payment-Simulation must be one of " + PAYMENT_SIMULATIONS,
                        null);
            }
        }
        return orders.placeOrder(idempotencyKey, caller.userId(), reservationId, paymentSimulation);
    }

    public Views.Order order(Caller caller, String orderId) {
        return orders.getOrder(orderId, caller.requesterScope());
    }

    /** The caller's own orders, newest first. */
    public Views.OrderPage orders(Caller caller, int limit, Optional<String> cursor) {
        requirePageSize(limit);
        return orders.listOrders(caller.userId(), limit, cursor);
    }

    private static void requirePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ServiceProblem(
                    "validation-failed",
                    ServiceProblem.Kind.INVALID,
                    "limit must be between 1 and " + MAX_PAGE_SIZE,
                    null);
        }
    }
}
