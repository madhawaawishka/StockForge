package com.stockforge.order.domain.model;

/**
 * Where the checkout saga of an order stands (spec §16 B). Stored on the order row: the orchestrator's state is part of
 * the order aggregate, so a crash between steps never loses progress.
 *
 * <pre>
 *   PAYMENT_REQUESTED ---- payment succeeded ----> CONFIRMING_RESERVATION ---- reservation confirmed --> COMPLETED
 *          |                                              |
 *          +-- payment failed / timed out --> COMPLETED   +-- reservation gone --> REFUNDING -- refunded --> COMPLETED
 * </pre>
 *
 * The distinction between the first two steps matters: the saga timeout may cancel an order only while the payment is
 * still outstanding, never once the payment succeeded and the reservation confirmation is in flight.
 */
public enum SagaStep {
    PAYMENT_REQUESTED,
    CONFIRMING_RESERVATION,
    REFUNDING,
    COMPLETED
}
