package com.stockforge.gateway.domain.port;

import com.stockforge.gateway.domain.model.Views;
import java.util.Optional;
import java.util.UUID;

/**
 * The Order service as the gateway uses it; failures surface as
 * {@link com.stockforge.gateway.domain.model.ServiceProblem}.
 */
public interface OrderApi {

    /**
     * Places the order for a reservation; payment completes asynchronously.
     *
     * @param paymentSimulation simulated payment outcome for tests, already vetted by the gateway's configuration
     */
    Views.Idempotent<Views.Order> placeOrder(
            String idempotencyKey, UUID userId, String reservationId, Optional<String> paymentSimulation);

    Views.Order getOrder(String orderId, String requesterScope);

    Views.OrderPage listOrders(UUID userId, int limit, Optional<String> cursor);

    int replayDeadLetters(String topic, int maxMessages, UUID actorId);
}
