package com.stockforge.payment.testsupport;

import com.stockforge.payment.domain.model.OrderId;
import com.stockforge.payment.domain.model.Payment;
import com.stockforge.payment.domain.model.PaymentId;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<PaymentId, Payment> storage = new ConcurrentHashMap<>();

    @Override
    public void insert(Payment payment) {
        storage.put(payment.id(), payment);
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
        return storage.values().stream()
                .filter(p -> p.orderId().equals(orderId))
                .findFirst();
    }

    @Override
    public void update(Payment payment) {
        storage.put(payment.id(), payment);
    }
}
