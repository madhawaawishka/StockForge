package com.stockforge.payment.application;

import com.stockforge.payment.domain.model.Payment;

public sealed interface ProcessPaymentResult {

    record Success(Payment payment) implements ProcessPaymentResult {}

    record Failure(Payment payment, String reason) implements ProcessPaymentResult {}

    record TimedOut(Payment payment) implements ProcessPaymentResult {}

    record AlreadyProcessed(Payment payment) implements ProcessPaymentResult {}
}
