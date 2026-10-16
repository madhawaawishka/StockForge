package com.stockforge.payment.domain.port;

import com.stockforge.payment.domain.model.Payment;
import java.util.Optional;

/**
 * The external payment provider (simulated, spec §5: no real money). Calls are remote and bounded by a timeout, so they
 * are never made inside a database transaction (spec §15). Both operations are idempotent per payment id, which makes
 * retrying after a crash safe.
 */
public interface PaymentProvider {

    /**
     * Charges the payment.
     *
     * @param requestedOutcome outcome a test or demo asked for (FR-6); honoured only where simulation control is
     *     enabled
     */
    ChargeOutcome charge(Payment payment, Optional<String> requestedOutcome);

    /** Refunds a captured payment. */
    void refund(Payment payment);

    /** What the provider answered. */
    enum ChargeOutcome {
        SUCCEEDED,
        DECLINED,
        /** The provider did not answer within the payment timeout (spec §23 A). */
        TIMED_OUT,
        /** The answer was lost entirely; the payment stays PENDING and the order saga times out. */
        NO_RESPONSE
    }
}
