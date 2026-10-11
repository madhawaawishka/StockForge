package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.ReservationGatewayUseCase;
import com.stockforge.gateway.domain.model.ReservationView;
import com.stockforge.gateway.domain.model.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationGatewayController {

    private final ReservationGatewayUseCase useCase;

    public ReservationGatewayController(ReservationGatewayUseCase useCase) {
        this.useCase = useCase;
    }

    public record CreateReservationBody(
            @NotBlank(message = "productId must not be blank")
            String productId,

            @Positive(message = "quantity must be greater than zero")
            int quantity) {}

    @PostMapping
    public ResponseEntity<ReservationView> createReservation(
            @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreateReservationBody body,
            Principal principal) {

        UserIdentity caller = resolveCaller(principal);
        ReservationView reservation = useCase.reserve(idempotencyKey, body.productId(), caller, body.quantity());
        URI location = URI.create("/api/v1/reservations/" + reservation.id());
        return ResponseEntity.created(location).body(reservation);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationView> getReservation(@PathVariable("id") String id, Principal principal) {
        UserIdentity caller = resolveCaller(principal);
        return useCase.getReservation(id, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationView> cancelReservation(@PathVariable("id") String id, Principal principal) {
        UserIdentity caller = resolveCaller(principal);
        return useCase.cancelReservation(id, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static UserIdentity resolveCaller(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new AccessDeniedException("Unauthenticated request");
        }
        try {
            return UserIdentity.of(UUID.fromString(principal.getName()));
        } catch (IllegalArgumentException e) {
            return UserIdentity.of(UUID.nameUUIDFromBytes(principal.getName().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
