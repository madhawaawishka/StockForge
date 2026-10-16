package com.stockforge.payment.domain.port;

import com.stockforge.payment.domain.model.PaymentId;

/** Generates time-ordered payment ids (ADR-005). */
public interface IdGenerator {

    PaymentId newPaymentId();
}
