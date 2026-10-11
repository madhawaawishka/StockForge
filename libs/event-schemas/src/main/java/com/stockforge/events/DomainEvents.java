package com.stockforge.events;

import java.time.Instant;
import java.util.UUID;

/** Event and command payload records for cross-service asynchronous workflows (spec §16, §18). */
public final class DomainEvents {

    private DomainEvents() {}

    // --- Inventory Events & Commands ---

    public record ReservationCreated(
            UUID reservationId, UUID userId, UUID productId, int quantity, Instant expiresAt) {}

    public record ReservationConfirmed(UUID reservationId, UUID productId, int quantity) {}

    public record ReservationCancelled(UUID reservationId, UUID userId, UUID productId, int quantity) {}

    public record ReservationExpired(UUID reservationId, UUID productId, int quantity) {}

    public record ConfirmReservationCommand(UUID reservationId) {}

    public record ReleaseReservationCommand(UUID reservationId, String reason) {}

    // --- Order Events ---

    public record OrderCreated(UUID orderId, UUID userId, UUID reservationId, String totalAmount, String currency) {}

    public record OrderConfirmed(UUID orderId, UUID userId, UUID reservationId) {}

    public record OrderCancelled(UUID orderId, UUID userId, UUID reservationId, String reason) {}

    // --- Payment Events & Commands ---

    public record RequestPaymentCommand(
            UUID orderId, UUID userId, String amount, String currency, String idempotencyKey) {}

    public record PaymentSucceeded(UUID paymentId, UUID orderId, String amount, String currency) {}

    public record PaymentFailed(UUID paymentId, UUID orderId, String reason) {}

    public record PaymentTimedOut(UUID paymentId, UUID orderId) {}

    public record RefundPaymentCommand(UUID orderId, String reason) {}

    public record PaymentRefunded(UUID paymentId, UUID orderId, String amount, String currency) {}

    // --- Kafka Topic Constants (spec §18) ---

    public static final String TOPIC_ORDER_EVENTS = "order-events";
    public static final String TOPIC_INVENTORY_EVENTS = "inventory-events";
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    public static final String TOPIC_PAYMENT_COMMANDS = "payment-commands";
    public static final String TOPIC_INVENTORY_COMMANDS = "inventory-commands";
}
