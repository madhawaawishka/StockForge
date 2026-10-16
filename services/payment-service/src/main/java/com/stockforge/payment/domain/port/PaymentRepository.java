package com.stockforge.payment.domain.port;

import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import java.util.Optional;

/** Payments; at most one per order ({@code UNIQUE (order_id)}). */
public interface PaymentRepository {

    /** Stores the payment unless the order already has one; returns the order's payment either way. */
    Payment insertOrGetForOrder(Payment payment);

    Optional<Payment> findByOrderId(OrderId orderId);

    /**
     * Stores the transition only if the row is still in {@code before}'s status (spec §16 A).
     *
     * @return {@code false} if another transition got there first
     */
    boolean saveTransition(Payment before, Payment after);
}
