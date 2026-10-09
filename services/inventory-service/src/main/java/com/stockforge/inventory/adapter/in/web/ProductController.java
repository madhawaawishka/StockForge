package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.application.CreateProductResult;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ProductPage;
import com.stockforge.inventory.domain.model.ProductId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Catalog and availability endpoints. Translates HTTP to use cases and back; contains no business logic. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Products")
class ProductController {

    private final CreateProductUseCase createProduct;
    private final ProductCatalogQueries catalog;

    ProductController(CreateProductUseCase createProduct, ProductCatalogQueries catalog) {
        this.createProduct = createProduct;
        this.catalog = catalog;
    }

    @PostMapping("/admin/products")
    @Operation(summary = "Create a product with its initial stock")
    ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request, UriComponentsBuilder uriBuilder) {
        return switch (createProduct.execute(request.toCommand())) {
            case CreateProductResult.Created created -> {
                URI location = uriBuilder
                        .path("/api/v1/products/{id}")
                        .buildAndExpand(created.product().id().value())
                        .toUri();
                yield ResponseEntity.created(location).body(ProductResponse.from(created.product()));
            }
            case CreateProductResult.SkuAlreadyExists conflict -> throw ApiProblems.skuAlreadyExists(conflict.sku());
        };
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "Get a product")
    ProductResponse getProduct(@PathVariable UUID productId) {
        ProductId id = ProductId.of(productId);
        return catalog.findProduct(id).map(ProductResponse::from).orElseThrow(() -> ApiProblems.productNotFound(id));
    }

    @GetMapping("/products")
    @Operation(summary = "List products", description = "Keyset-paginated; pass nextCursor to fetch the next page")
    ProductPageResponse listProducts(
            @RequestParam(defaultValue = "20") @Min(1) @Max(ProductCatalogQueries.MAX_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor) {
        ProductPage page =
                cursor == null ? catalog.firstPage(limit) : catalog.pageAfter(PageCursor.decode(cursor), limit);
        return ProductPageResponse.from(page);
    }

    @GetMapping("/products/{productId}/availability")
    @Operation(summary = "Get current stock availability for a product")
    AvailabilityResponse getAvailability(@PathVariable UUID productId) {
        ProductId id = ProductId.of(productId);
        return catalog.findInventory(id)
                .map(AvailabilityResponse::from)
                .orElseThrow(() -> ApiProblems.productNotFound(id));
    }
}
