package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.AdminUseCases;
import com.stockforge.gateway.domain.model.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative operations (spec §12, FR-10). ADMIN role only; every action is audited by the owning service. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration")
@SecurityRequirement(name = "bearer")
class AdminController {

    private final AdminUseCases admin;

    AdminController(AdminUseCases admin) {
        this.admin = admin;
    }

    record MoneyBody(
            @NotBlank @Pattern(regexp = "\\d{1,15}(\\.\\d{1,4})?", message = "must be a decimal amount")
            String amount,

            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code")
            String currency) {}

    record CreateProductBody(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{2,63}")
            String sku,

            @NotBlank @Size(max = 200) String name,
            @NotNull @Valid MoneyBody price,
            @Min(0) @Max(1_000_000) int initialStock) {}

    record StockAdjustmentBody(
            @Min(-1_000_000) @Max(1_000_000) int delta,
            @NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/products")
    @Operation(summary = "Create a product with its initial stock")
    ResponseEntity<Views.Product> createProduct(
            @Valid @RequestBody CreateProductBody body, Authentication authentication) {
        Views.Product product = admin.createProduct(
                Callers.of(authentication),
                body.sku(),
                body.name(),
                body.price().amount(),
                body.price().currency(),
                body.initialStock());
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .body(product);
    }

    @PostMapping("/products/{productId}/stock-adjustments")
    @Operation(summary = "Audited stock adjustment", description = "Positive delta adds stock, negative writes it off")
    ResponseEntity<Views.Availability> adjustStock(
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(
                            regexp = ShopperController.IDEMPOTENCY_KEY,
                            message = ShopperController.IDEMPOTENCY_KEY_MESSAGE)
                    String idempotencyKey,
            @Valid @RequestBody StockAdjustmentBody body,
            Authentication authentication) {
        Views.Idempotent<Views.Availability> result = admin.adjustStock(
                Callers.of(authentication), idempotencyKey, productId.toString(), body.delta(), body.reason());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.value());
    }

    /** 202: the messages are republished; their consumers process them asynchronously. */
    @PostMapping("/dead-letters/{topic}/replay")
    @Operation(summary = "Replay dead-lettered messages of a topic")
    ResponseEntity<Map<String, Object>> replayDeadLetters(
            @PathVariable @Pattern(regexp = "[a-z-]{1,64}") String topic,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int maxMessages,
            Authentication authentication) {
        int replayed = admin.replayDeadLetters(Callers.of(authentication), topic, maxMessages);
        return ResponseEntity.accepted().body(Map.of("topic", topic, "replayed", replayed));
    }
}
