package com.stockforge.events;

import java.util.UUID;

/**
 * Messages owned by the Payment bounded context: commands on {@link Topics#PAYMENT_COMMANDS} and events on
 * {@link Topics#PAYMENT_EVENTS}. All are keyed by order id, so the charge and refund of one order stay in order.
 */
public final class PaymentMessages {

    public static final String AGGREGATE_PAYMENT = "Payment";

    private PaymentMessages() {}

    // --- Commands -----------------------------------------------------------------------------------------------

    /**
     * Saga step 3: charge the order total.
     *
     * @param simulatedOutcome optional outcome for deterministic tests (FR-6); {@code null} lets the simulator decide.
     *     Honoured only when the payment service runs with simulation enabled.
     */
    public record RequestPayment(UUID orderId, UUID userId, String amount, String currency, String simulatedOutcome) {
        public static final String TYPE = "RequestPayment";
    }

    /** Compensation for saga step 3. */
    public record RefundPayment(UUID orderId, String reason) {
        public static final String TYPE = "RefundPayment";
    }

    // --- Events -------------------------------------------------------------------------------------------------

    public record PaymentSucceeded(UUID paymentId, UUID orderId, String amount, String currency) {
        public static final String TYPE = "PaymentSucceeded";
    }

    public record PaymentFailed(UUID paymentId, UUID orderId, String reason) {
        public static final String TYPE = "PaymentFailed";
    }

    /** The payment provider did not answer within the payment timeout (spec §23 A). */
    public record PaymentTimedOut(UUID paymentId, UUID orderId) {
        public static final String TYPE = "PaymentTimedOut";
    }

    public record PaymentRefunded(UUID paymentId, UUID orderId, String amount, String currency) {
        public static final String TYPE = "PaymentRefunded";
    }
}
