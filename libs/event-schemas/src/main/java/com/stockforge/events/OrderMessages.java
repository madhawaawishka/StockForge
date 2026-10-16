package com.stockforge.events;

import java.util.UUID;

/** Events owned by the Ordering bounded context, published on {@link Topics#ORDER_EVENTS} and keyed by order id. */
public final class OrderMessages {

    public static final String AGGREGATE_ORDER = "Order";

    private OrderMessages() {}

    /** Monetary amounts are decimal strings, never floating point. */
    public record OrderCreated(UUID orderId, UUID userId, UUID reservationId, String totalAmount, String currency) {
        public static final String TYPE = "OrderCreated";
    }

    public record OrderConfirmed(UUID orderId, UUID userId, UUID reservationId, String totalAmount, String currency) {
        public static final String TYPE = "OrderConfirmed";
    }

    public record OrderCancelled(UUID orderId, UUID userId, UUID reservationId, String reason) {
        public static final String TYPE = "OrderCancelled";
    }

    /** Payment succeeded but the reservation could no longer be confirmed, so the payment is being refunded. */
    public record OrderRefundPending(UUID orderId, UUID userId, UUID reservationId, String reason) {
        public static final String TYPE = "OrderRefundPending";
    }

    public record OrderRefunded(UUID orderId, UUID userId, String amount, String currency) {
        public static final String TYPE = "OrderRefunded";
    }
}
