package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.OrderGatewayUseCase;
import com.stockforge.gateway.domain.model.OrderItemView;
import com.stockforge.gateway.domain.model.OrderView;
import com.stockforge.gateway.domain.model.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderGatewayController {

    private final OrderGatewayUseCase useCase;

    public OrderGatewayController(OrderGatewayUseCase useCase) {
        this.useCase = useCase;
    }

    public record CreateOrderItemRequest(
            @NotBlank String productId,
            @Positive int quantity,
            @NotBlank String unitPrice,
            @NotBlank String currency) {}

    public record CreateOrderRequestBody(
            @NotBlank String reservationId, @NotEmpty List<@Valid CreateOrderItemRequest> items) {}

    @PostMapping
    public ResponseEntity<OrderView> createOrder(
            @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequestBody body,
            Principal principal) {

        UserIdentity caller = resolveCaller(principal);
        List<OrderItemView> items = body.items().stream()
                .map(i -> new OrderItemView(i.productId(), i.quantity(), i.unitPrice(), i.currency()))
                .toList();

        OrderView order = useCase.createOrder(idempotencyKey, caller, body.reservationId(), items);
        URI location = URI.create("/api/v1/orders/" + order.id());
        return ResponseEntity.accepted().location(location).body(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderView> getOrder(@PathVariable("id") String id, Principal principal) {
        UserIdentity caller = resolveCaller(principal);
        return useCase.getOrder(id, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<OrderView>> listOrders(
            @RequestParam(name = "limit", defaultValue = "20") int limit, Principal principal) {
        UserIdentity caller = resolveCaller(principal);
        return ResponseEntity.ok(useCase.listOrders(caller, limit));
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
