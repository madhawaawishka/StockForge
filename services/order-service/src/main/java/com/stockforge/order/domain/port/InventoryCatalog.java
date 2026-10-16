package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * Read access to the Inventory service, used before an order is placed (spec §16 B, step 2).
 *
 * <p>Implementations make a remote call with a deadline, so callers must never hold a database transaction open while
 * calling (spec §15). An unreachable Inventory service is reported with {@link InventoryUnavailableException}.
 */
public interface InventoryCatalog {

    /** The reservation if it exists and belongs to {@code owner}; another user's reservation is reported as absent. */
    Optional<ReservationSnapshot> findReservation(ReservationId id, UserId owner);

    /** The current catalog price of a product; it becomes the price snapshot of the order line. */
    Optional<Money> findPrice(ProductId productId);

    /**
     * What the order needs to know about a reservation.
     *
     * @param status reservation status as reported by Inventory, for example {@code PENDING}
     */
    record ReservationSnapshot(
            ReservationId id, UserId owner, ProductId productId, int quantity, String status, Instant expiresAt) {

        public boolean isPending() {
            return "PENDING".equals(status);
        }
    }

    /** The Inventory service did not answer in time or its circuit breaker is open. */
    class InventoryUnavailableException extends RuntimeException {

        public InventoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
