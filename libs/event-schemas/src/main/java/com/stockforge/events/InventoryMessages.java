package com.stockforge.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Messages owned by the Inventory bounded context: events on {@link Topics#INVENTORY_EVENTS} and commands on
 * {@link Topics#INVENTORY_COMMANDS}. All are keyed by reservation id.
 */
public final class InventoryMessages {

    public static final String AGGREGATE_RESERVATION = "Reservation";

    private InventoryMessages() {}

    // --- Events -------------------------------------------------------------------------------------------------

    public record ReservationCreated(UUID reservationId, UUID userId, UUID productId, int quantity, Instant expiresAt) {
        public static final String TYPE = "ReservationCreated";
    }

    /** Reserved stock became sold because the saga confirmed the reservation (point of no return, spec §16 B). */
    public record ReservationConfirmed(UUID reservationId, UUID orderId, UUID productId, int quantity) {
        public static final String TYPE = "ReservationConfirmed";
    }

    /**
     * The saga asked to confirm a reservation that is no longer PENDING (it expired or was cancelled). The order
     * follows the refund path (spec §16 C).
     */
    public record ReservationConfirmationRejected(UUID reservationId, UUID orderId, String reservationStatus) {
        public static final String TYPE = "ReservationConfirmationRejected";
    }

    /** Reserved stock returned to available because the user cancelled or the saga compensated. */
    public record ReservationReleased(
            UUID reservationId, UUID userId, UUID productId, int quantity, String reason, UUID orderId) {
        public static final String TYPE = "ReservationReleased";
    }

    /** Reserved stock returned to available because the reservation passed its expiry time. */
    public record ReservationExpired(UUID reservationId, UUID userId, UUID productId, int quantity) {
        public static final String TYPE = "ReservationExpired";
    }

    // --- Commands -----------------------------------------------------------------------------------------------

    public record ConfirmReservation(UUID reservationId, UUID orderId) {
        public static final String TYPE = "ConfirmReservation";
    }

    /** Compensation for saga step 1: release the stock of a reservation that will not be paid. */
    public record ReleaseReservation(UUID reservationId, UUID orderId, String reason) {
        public static final String TYPE = "ReleaseReservation";
    }
}
