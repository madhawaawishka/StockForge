package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.adapter.in.TransientFailureRetry;
import com.stockforge.inventory.application.CancelReservationResult;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ReserveStockResult;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.model.ReservationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Internal reservation API of the inventory service (spec §12, §19).
 *
 * <p>Public clients use the API gateway, which authenticates the caller and takes the user id from the token. This
 * service-level API exists for integration tests and for benchmarking the concurrency strategies without the gateway
 * hop; it trusts the {@code userId} in the body, so it must only be reachable on the internal network (see
 * docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservations")
class ReservationController {

    private final ReserveStockUseCase reserveStock;
    private final CancelReservationUseCase cancelReservation;
    private final GetReservationUseCase getReservation;
    private final TransientFailureRetry retry;

    ReservationController(
            ReserveStockUseCase reserveStock,
            CancelReservationUseCase cancelReservation,
            GetReservationUseCase getReservation,
            TransientFailureRetry retry) {
        this.reserveStock = reserveStock;
        this.cancelReservation = cancelReservation;
        this.getReservation = getReservation;
        this.retry = retry;
    }

    @PostMapping
    @Operation(summary = "Reserve stock with idempotency key")
    ResponseEntity<ReservationResponse> reserve(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReserveStockRequest request,
            UriComponentsBuilder uriBuilder) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiProblems.missingIdempotencyKey();
        }

        ReserveStockResult result = retry.execute(() -> reserveStock.execute(request.toCommand(idempotencyKey)));

        return switch (result) {
            case ReserveStockResult.Reserved r -> {
                URI location = uriBuilder
                        .path("/api/v1/reservations/{id}")
                        .buildAndExpand(r.reservation().id().value())
                        .toUri();
                // A replay returns the original status and body, flagged for transparency (spec §19).
                ResponseEntity.BodyBuilder created = ResponseEntity.created(location);
                if (r.replayed()) {
                    created.header("Idempotent-Replayed", "true");
                }
                yield created.body(ReservationResponse.from(r.reservation()));
            }
            case ReserveStockResult.InsufficientStock i ->
                throw ApiProblems.insufficientStock(i.productId(), i.requestedQuantity());
            case ReserveStockResult.PurchaseLimitExceeded p ->
                throw ApiProblems.purchaseLimitExceeded(p.userId(), p.limit());
            case ReserveStockResult.ProductNotFound p -> throw ApiProblems.productNotFound(p.productId());
            case ReserveStockResult.OptimisticConflict o -> throw ApiProblems.optimisticConflict(o.productId());
            case ReserveStockResult.IdempotencyKeyReused reused -> throw ApiProblems.idempotencyKeyReused();
        };
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get reservation status")
    ReservationResponse get(@PathVariable UUID id) {
        ReservationId reservationId = ReservationId.of(id);
        return getReservation
                .execute(reservationId, Optional.empty())
                .map(ReservationResponse::from)
                .orElseThrow(() -> ApiProblems.reservationNotFound(reservationId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending reservation")
    ResponseEntity<ReservationResponse> cancel(@PathVariable UUID id) {
        ReservationId reservationId = ReservationId.of(id);
        CancelReservationResult result =
                retry.execute(() -> cancelReservation.execute(reservationId, Optional.empty()));

        return switch (result) {
            case CancelReservationResult.Cancelled c -> ResponseEntity.ok(ReservationResponse.from(c.reservation()));
            case CancelReservationResult.NotFound n -> throw ApiProblems.reservationNotFound(n.id());
            case CancelReservationResult.InvalidState s ->
                throw ApiProblems.invalidReservationState(s.id(), s.currentStatus());
        };
    }
}
