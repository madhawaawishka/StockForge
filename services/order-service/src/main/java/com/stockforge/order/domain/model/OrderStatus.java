package com.stockforge.order.domain.model;

/**
 * Lifecycle states of an Order (spec §16 A).
 *
 * <pre>
 *   PENDING_PAYMENT ---- payment succeeded + reservation confirmed ----> CONFIRMED
 *         |
 *         +------------- payment failed / timed out / reservation released -> CANCELLED
 *         |
 *         +------------- payment succeeded but reservation already expired -> REFUND_PENDING -> REFUNDED
 * </pre>
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case PENDING_PAYMENT -> next == CONFIRMED || next == CANCELLED || next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED;
            case CONFIRMED, CANCELLED, REFUNDED -> false; // Terminal states
        };
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == REFUNDED;
    }
}
