package com.stockforge.inventory.adapter.in.web;

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
 * Reservation REST controller (spec §12, §16, §19).
 *
 * <p>Translates HTTP requests into application commands and results into HTTP responses or RFC 9457 problems.
 */
@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservations")
class ReservationController {

    private final ReserveStockUseCase reserveStock;
    private final CancelReservationUseCase cancelReservation;
    private final GetReservationUseCase getReservation;

    ReservationController(
            ReserveStockUseCase reserveStock,
            CancelReservationUseCase cancelReservation,
            GetReservationUseCase getReservation) {
        this.reserveStock = reserveStock;
        this.cancelReservation = cancelReservation;
        this.getReservation = getReservation;
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

        ReserveStockResult result = reserveStock.execute(request.toCommand(idempotencyKey.trim()));

        return switch (result) {
            case ReserveStockResult.Reserved r -> {
                URI location = uriBuilder
                        .path("/api/v1/reservations/{id}")
                        .buildAndExpand(r.reservation().id().value())
                        .toUri();
                yield ResponseEntity.created(location).body(ReservationResponse.from(r.reservation()));
            }
            case ReserveStockResult.IdempotentReplay r ->
                ResponseEntity.ok()
                        .header("Idempotent-Replayed", "true")
                        .body(ReservationResponse.from(r.reservation()));
            case ReserveStockResult.InsufficientStock i ->
                throw ApiProblems.insufficientStock(i.productId(), i.requestedQuantity());
            case ReserveStockResult.PurchaseLimitExceeded p ->
                throw ApiProblems.purchaseLimitExceeded(p.userId(), p.maxLimit());
            case ReserveStockResult.ProductNotFound p -> throw ApiProblems.productNotFound(p.productId());
            case ReserveStockResult.OptimisticConflict o -> throw ApiProblems.optimisticConflict(o.productId());
            case ReserveStockResult.IdempotencyConflict c -> throw ApiProblems.idempotencyConflict(c.message());
        };
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get reservation status")
    ReservationResponse get(@PathVariable UUID id) {
        ReservationId reservationId = ReservationId.of(id);
        return getReservation
                .execute(reservationId)
                .map(ReservationResponse::from)
                .orElseThrow(() -> ApiProblems.reservationNotFound(reservationId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending reservation")
    ResponseEntity<ReservationResponse> cancel(@PathVariable UUID id) {
        ReservationId reservationId = ReservationId.of(id);
        CancelReservationResult result = cancelReservation.execute(reservationId);

        return switch (result) {
            case CancelReservationResult.Cancelled c -> ResponseEntity.ok(ReservationResponse.from(c.reservation()));
            case CancelReservationResult.NotFound n -> throw ApiProblems.reservationNotFound(n.id());
            case CancelReservationResult.InvalidState s ->
                throw ApiProblems.invalidReservationState(s.id(), s.currentStatus());
        };
    }
}
