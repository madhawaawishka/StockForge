package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.application.ProductGatewayQueries;
import com.stockforge.gateway.domain.model.AvailabilityView;
import com.stockforge.gateway.domain.model.ProductView;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductGatewayController {

    private final ProductGatewayQueries queries;

    public ProductGatewayController(ProductGatewayQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<List<ProductView>> listProducts(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(queries.listProducts(cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> getProduct(@PathVariable("id") String id) {
        return queries.getProduct(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<AvailabilityView> getAvailability(@PathVariable("id") String id) {
        return queries.getAvailability(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
