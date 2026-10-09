package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.application.ProductPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

record ProductPageResponse(
        List<ProductResponse> items,

        @Schema(description = "Opaque cursor for the next page; null on the last page", nullable = true)
        String nextCursor) {

    static ProductPageResponse from(ProductPage page) {
        return new ProductPageResponse(
                page.items().stream().map(ProductResponse::from).toList(),
                page.nextAfter().map(PageCursor::encode).orElse(null));
    }
}
