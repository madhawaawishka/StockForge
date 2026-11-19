package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.StorefrontUseCases;
import com.stockforge.gateway.domain.model.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
            @Schema(example = "0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f", description = "Product UUID") @NotNull
            UUID productId,

            @Schema(example = "1", description = "Quantity to reserve (1-100)") @Min(1) @Max(100)
            int quantity) {}

    record PlaceOrderRequest(
            @Schema(example = "0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e7a", description = "Reservation UUID") @NotNull
            UUID reservationId) {}

    @PostMapping("/reservations")
    @Operation(
            summary = "Reserve stock",
            description =
                    "Atomic conditional UPDATE in PostgreSQL: concurrent buyers can never oversell. Idempotent per"
                            + " Idempotency-Key; a retry returns the original result with Idempotent-Replayed: true.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Reserved; the reservation expires if not ordered in time"),
        @ApiResponse(responseCode = "400", description = "validation-failed, missing-idempotency-key"),
        @ApiResponse(responseCode = "401", description = "unauthorized"),
        @ApiResponse(responseCode = "404", description = "product-not-found"),
        @ApiResponse(responseCode = "409", description = "insufficient-stock: sold out"),
        @ApiResponse(
                responseCode = "422",
                description = "purchase-limit-exceeded, or idempotency-key-reused for a different request"),
        @ApiResponse(responseCode = "429", description = "rate-limit-exceeded; see Retry-After"),
        @ApiResponse(responseCode = "503", description = "service-unavailable: circuit open or bulkhead full")
    })
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
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The reservation", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", description = "reservation-not-found, also when it belongs to another user")
    })
    Views.Reservation reservation(@PathVariable String reservationId, Authentication authentication) {
        return storefront.reservation(Callers.of(authentication), reservationId);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    @Operation(summary = "Cancel a pending reservation")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cancelled; the units are available again",
                useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", description = "reservation-not-found"),
        @ApiResponse(responseCode = "409", description = "invalid-reservation-state: not PENDING any more")
    })
    Views.Reservation cancel(@PathVariable String reservationId, Authentication authentication) {
        return storefront.cancelReservation(Callers.of(authentication), reservationId);
    }

    /** 202 Accepted: the order exists, payment completes asynchronously; poll the Location (spec §12). */
    @PostMapping("/orders")
    @Operation(
            summary = "Place an order for a reservation",
            description = "Starts the checkout saga: payment runs asynchronously over Kafka. Poll the Location header."
                    + " A failed payment compensates: the order is CANCELLED and the stock is released.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Accepted: the order is PENDING_PAYMENT; see Location"),
        @ApiResponse(
                responseCode = "400",
                description = "validation-failed, missing-idempotency-key, payment-simulation-disabled"),
        @ApiResponse(responseCode = "404", description = "reservation-not-found, also when it belongs to another user"),
        @ApiResponse(responseCode = "409", description = "reservation-not-orderable, reservation-already-ordered"),
        @ApiResponse(responseCode = "422", description = "idempotency-key-reused"),
        @ApiResponse(responseCode = "429", description = "rate-limit-exceeded; see Retry-After")
    })
    ResponseEntity<Views.Order> placeOrder(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY, message = IDEMPOTENCY_KEY_MESSAGE)
                    String idempotencyKey,
            @Parameter(
                            description =
                                    "Test hook, local stack only: forces the simulated payment outcome. Use FAILED"
                                            + " to watch the saga compensate.",
                            schema = @Schema(allowableValues = {"SUCCEEDED", "FAILED", "TIMED_OUT", "NO_RESPONSE"}))
                    @RequestHeader(name = "X-Payment-Simulation", required = false)
                    String paymentSimulation,
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
    @Operation(
            summary = "Order status and history",
            description = "Includes the audit trail of every state change. 404 also for other users' orders (BOLA).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The order with its status history",
                useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", description = "order-not-found, also when it belongs to another user")
    })
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
