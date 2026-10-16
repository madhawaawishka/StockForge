package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.DomainValidationException;
import com.stockforge.inventory.domain.model.IdempotencyKeys;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.UserId;
import java.util.Objects;

/**
 * An administrator adds received units (positive delta) or writes off available units (negative delta), FR-10.
 *
 * @param actor the authenticated administrator; scopes the idempotency key and is recorded in the audit log
 */
public record AdjustStockCommand(String idempotencyKey, UserId actor, ProductId productId, int delta, String reason) {

    public static final int MAX_REASON_LENGTH = 500;

    public AdjustStockCommand {
        IdempotencyKeys.requireValid(idempotencyKey);
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (delta == 0) {
            throw new DomainValidationException("Stock adjustment must not be zero");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException("Reason must be 1-" + MAX_REASON_LENGTH + " characters");
        }
    }
}
