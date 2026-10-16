package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.StorefrontUseCases;
import com.stockforge.gateway.domain.model.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reservations and orders of the authenticated shopper (spec §12). The user id is the token's subject; request bodies
 * are separate DTOs, so a client cannot set fields such as the owner or the price (spec §28: mass assignment).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Checkout")
@SecurityRequirement(name = "bearer")
class ShopperController {

    /** Printable ASCII without spaces, as the services require (spec §19). */
    static final String IDEMPOTENCY_KEY = "[!-~]{1,128}";

    static final String IDEMPOTENCY_KEY_MESSAGE = "must be 1-128 printable ASCII characters without spaces";

    private final StorefrontUseCases storefront;

    ShopperController(StorefrontUseCases storefront) {
        this.storefront = storefront;
    }

    record ReserveRequest(
            @NotNull UUID productId, @Min(1) @Max(100) int quantity) {}

    record PlaceOrderRequest(@NotNull UUID reservationId) {}

    @PostMapping("/reservations")
    @Operation(
            summary = "Reserve stock",
            description = "Idempotent per Idempotency-Key; a retry returns the original result")
    ResponseEntity<Views.Reservation> reserve(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY, message = IDEMPOTENCY_KEY_MESSAGE)
                    String idempotencyKey,
            @Valid @RequestBody ReserveRequest body,
            Authentication authentication) {
        Views.Idempotent<Views.Reservation> result = storefront.reserve(
                Callers.of(authentication), idempotencyKey, body.productId().toString(), body.quantity());
        return replayAware(
                ResponseEntity.created(
                        URI.create("/api/v1/reservations/" + result.value().id())),
                result);
    }

    @GetMapping("/reservations/{reservationId}")
    @Operation(summary = "Get a reservation", description = "404 also for other users' reservations")
    Views.Reservation reservation(@PathVariable String reservationId, Authentication authentication) {
        return storefront.reservation(Callers.of(authentication), reservationId);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    @Operation(summary = "Cancel a pending reservation")
    Views.Reservation cancel(@PathVariable String reservationId, Authentication authentication) {
        return storefront.cancelReservation(Callers.of(authentication), reservationId);
    }

    /** 202 Accepted: the order exists, payment completes asynchronously; poll the Location (spec §12). */
    @PostMapping("/orders")
    @Operation(summary = "Place an order for a reservation", description = "Payment runs asynchronously")
    ResponseEntity<Views.Order> placeOrder(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY, message = IDEMPOTENCY_KEY_MESSAGE)
                    String idempotencyKey,
            @RequestHeader(name = "X-Payment-Simulation", required = false) String paymentSimulation,
            @Valid @RequestBody PlaceOrderRequest body,
            Authentication authentication) {
        Views.Idempotent<Views.Order> result = storefront.placeOrder(
                Callers.of(authentication),
                idempotencyKey,
                body.reservationId().toString(),
                Optional.ofNullable(paymentSimulation));
        return replayAware(
                ResponseEntity.accepted()
                        .location(URI.create("/api/v1/orders/" + result.value().id())),
                result);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Order status and history", description = "404 also for other users' orders")
    Views.Order order(@PathVariable String orderId, Authentication authentication) {
        return storefront.order(Callers.of(authentication), orderId);
    }

    @GetMapping("/orders")
    @Operation(summary = "The caller's orders, newest first")
    Views.OrderPage orders(
            @RequestParam(defaultValue = "20") @Min(1) @Max(StorefrontUseCases.MAX_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor,
            Authentication authentication) {
        return storefront.orders(Callers.of(authentication), limit, Optional.ofNullable(cursor));
    }

    /** A replay returns the original status and body, flagged with {@code Idempotent-Replayed} (spec §19). */
    private static <T> ResponseEntity<T> replayAware(ResponseEntity.BodyBuilder response, Views.Idempotent<T> result) {
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.value());
    }
}
