package com.stockforge.payment.domain.port;

import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import java.util.Optional;

public interface PaymentRepository {

    void insert(Payment payment);

    Optional<Payment> findById(PaymentId id);

    Optional<Payment> findByOrderId(OrderId orderId);

    void update(Payment payment);
}
