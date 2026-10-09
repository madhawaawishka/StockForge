package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.ProductId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque pagination cursor. Clients must treat it as a black box, which lets the server change its contents (for
 * example to a composite sort key) without breaking the API.
 */
final class PageCursor {

    private PageCursor() {}

    static String encode(ProductId lastSeen) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(lastSeen.value().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @throws org.springframework.web.ErrorResponseException if the cursor was not produced by this API */
    static ProductId decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return ProductId.of(UUID.fromString(decoded));
        } catch (IllegalArgumentException malformed) {
            throw ApiProblems.invalidCursor();
        }
    }
}
