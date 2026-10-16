package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.StorefrontUseCases;
import com.stockforge.gateway.domain.model.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** Public catalog reads (spec §12): no authentication, served from the inventory cache where possible. */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Catalog")
class CatalogController {

    private final StorefrontUseCases storefront;

    CatalogController(StorefrontUseCases storefront) {
        this.storefront = storefront;
    }

    @GetMapping
    @Operation(summary = "List products", description = "Keyset pagination: pass nextCursor to get the next page")
    Views.ProductPage list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(StorefrontUseCases.MAX_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor) {
        return storefront.products(limit, Optional.ofNullable(cursor));
    }

    /** ETag from the product's last change: a client that has the current version gets 304 without a body. */
    @GetMapping("/{productId}")
    @Operation(summary = "Get a product")
    ResponseEntity<Views.Product> get(@PathVariable String productId, WebRequest request) {
        Views.Product product = storefront.product(productId);
        String etag = "\"" + product.id() + "-" + product.updatedAt() + "\"";
        return conditional(
                request, etag, CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic(), product);
    }

    /**
     * Shoppers poll availability during a sale, so it supports conditional requests too; {@code no-cache} makes clients
     * revalidate on every poll, which costs a 304 without a body while the stock has not changed.
     *
     * <p>The ETag is <em>weak</em> and covers the stock state only (counters and their change time), not the
     * {@code approximate} flag: the first read after a cache miss comes from the database and the next ones from the
     * cache, with the same numbers. The answers are semantically equivalent, which is exactly what a weak validator
     * expresses (RFC 9110 section 8.8.1); a strong ETag would turn every such poll into a needless 200.
     */
    @GetMapping("/{productId}/availability")
    @Operation(
            summary = "Approximate availability",
            description = "May be a few seconds old; reservations re-check stock")
    ResponseEntity<Views.Availability> availability(@PathVariable String productId, WebRequest request) {
        Views.Availability availability = storefront.availability(productId);
        String etag = "W/\""
                + String.join(
                        "-",
                        availability.asOf(),
                        String.valueOf(availability.totalQuantity()),
                        String.valueOf(availability.availableQuantity()),
                        String.valueOf(availability.reservedQuantity()),
                        String.valueOf(availability.soldQuantity()))
                + "\"";
        return conditional(request, etag, CacheControl.noCache(), availability);
    }

    private static <T> ResponseEntity<T> conditional(WebRequest request, String etag, CacheControl cache, T body) {
        if (request.checkNotModified(etag)) {
            return null; // Spring has already prepared the 304 response
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body);
    }
}
