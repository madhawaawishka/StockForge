package com.stockforge.order.application;

import com.stockforge.events.OrderMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import com.stockforge.order.domain.port.IdempotencyKeyRepository.IdempotencyRecord;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import com.stockforge.order.domain.port.OutboxPublisher.OutboundMessage;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of placing an order: order, idempotency key and outbox messages commit together or not at all (spec
 * §19, §20). A separate bean from {@link PlaceOrderUseCase} so its transaction starts only after the remote calls are
 * done.
 */
public class PlaceOrderTransaction {

    static final int STATUS_ACCEPTED = 202;
    static final int STATUS_ALREADY_ORDERED = 409;

    private final OrderRepository orders;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxPublisher outbox;
    private final CheckoutPolicy policy;

    public PlaceOrderTransaction(
            OrderRepository orders,
            IdempotencyKeyRepository idempotencyKeys,
            OutboxPublisher outbox,
            CheckoutPolicy policy) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /** The stored outcome for this key, if the key was used before. */
    @Transactional(readOnly = true)
    public Optional<PlaceOrderResult> replay(PlaceOrderCommand command, String requestHash) {
        return idempotencyKeys
                .find(command.idempotencyKey(), command.userId())
                .map(stored -> replayed(command, requestHash, stored));
    }

    @Transactional
    public PlaceOrderResult place(PlaceOrderCommand command, String requestHash, Order order, Instant now) {
        if (!idempotencyKeys.tryAcquire(
                command.idempotencyKey(), command.userId(), requestHash, now, now.plus(policy.idempotencyKeyTtl()))) {
            // A concurrent request with the same key committed first: answer with its outcome.
            return replay(command, requestHash).orElseThrow();
        }
        if (!orders.insertIfReservationUnordered(order)) {
            idempotencyKeys.complete(command.idempotencyKey(), command.userId(), STATUS_ALREADY_ORDERED, null);
            return new PlaceOrderResult.ReservationAlreadyOrdered(order.reservationId());
        }

        String orderId = order.id().toString();
        outbox.publish(new OutboundMessage(
                Topics.PAYMENT_COMMANDS,
                PaymentMessages.AGGREGATE_PAYMENT,
                orderId,
                PaymentMessages.RequestPayment.TYPE,
                new PaymentMessages.RequestPayment(
                        order.id().value(),
                        order.userId().value(),
                        order.totalAmount().amount().toPlainString(),
                        order.totalAmount().currency().getCurrencyCode(),
                        command.paymentSimulation().orElse(null)),
                orderId,
                orderId));
        outbox.publish(new OutboundMessage(
                Topics.ORDER_EVENTS,
                OrderMessages.AGGREGATE_ORDER,
                orderId,
                OrderMessages.OrderCreated.TYPE,
                new OrderMessages.OrderCreated(
                        order.id().value(),
                        order.userId().value(),
                        order.reservationId().value(),
                        order.totalAmount().amount().toPlainString(),
                        order.totalAmount().currency().getCurrencyCode()),
                orderId,
                orderId));
        idempotencyKeys.complete(command.idempotencyKey(), command.userId(), STATUS_ACCEPTED, orderId);
        return new PlaceOrderResult.Placed(order, false);
    }

    private PlaceOrderResult replayed(PlaceOrderCommand command, String requestHash, IdempotencyRecord stored) {
        if (!stored.requestHash().equals(requestHash)) {
            return new PlaceOrderResult.IdempotencyKeyReused(command.idempotencyKey());
        }
        Integer status = stored.responseStatus();
        if (status == null) {
            throw new IllegalStateException("Idempotency key " + command.idempotencyKey() + " has no stored outcome");
        }
        return switch (status) {
            case STATUS_ACCEPTED -> {
                OrderId id = OrderId.of(UUID.fromString(stored.response()));
                yield new PlaceOrderResult.Placed(
                        orders.findById(id)
                                .orElseThrow(() -> new IllegalStateException("Replayed order " + id + " is missing")),
                        true);
            }
            case STATUS_ALREADY_ORDERED -> new PlaceOrderResult.ReservationAlreadyOrdered(command.reservationId());
            default -> throw new IllegalStateException("Unknown stored outcome " + status);
        };
    }
}
