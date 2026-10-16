package com.stockforge.order.application;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.OrderMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.SagaStep;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import com.stockforge.order.domain.port.OutboxPublisher.OutboundMessage;
import com.stockforge.order.domain.port.ProcessedMessages;
import com.stockforge.order.domain.port.SagaObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrator of the checkout saga (spec §16, ADR-007). Reacts to payment and inventory events, moves the order
 * through its states and sends the next command, each step in one local transaction with its outbox messages:
 *
 * <pre>
 *   PaymentSucceeded                 -> ConfirmReservation              (step 4)
 *   ReservationConfirmed             -> order CONFIRMED, OrderConfirmed (step 5)
 *   PaymentFailed / PaymentTimedOut  -> order CANCELLED, ReleaseReservation          (compensation)
 *   payment timeout passed           -> order CANCELLED, ReleaseReservation          (compensation)
 *   ReservationConfirmationRejected  -> order REFUND_PENDING, RefundPayment          (spec §16 C)
 *   PaymentSucceeded after cancel    -> RefundPayment                                (late payment)
 *   PaymentRefunded                  -> order REFUNDED
 * </pre>
 *
 * <p>Every handler is idempotent twice over: the inbox skips a redelivered event id, and every transition is
 * conditional on the order's current status and saga step, so a racing or out-of-date event changes nothing.
 */
public class CheckoutSaga {

    public static final String PAYMENT_EVENTS_CONSUMER = "order-saga-payment-events";
    public static final String INVENTORY_EVENTS_CONSUMER = "order-saga-inventory-events";

    private final ProcessedMessages inbox;
    private final OrderRepository orders;
    private final OutboxPublisher outbox;
    private final SagaObserver observer;
    private final Clock clock;
    private final CheckoutPolicy policy;

    public CheckoutSaga(
            ProcessedMessages inbox,
            OrderRepository orders,
            OutboxPublisher outbox,
            SagaObserver observer,
            Clock clock,
            CheckoutPolicy policy) {
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Transactional
    public void onPaymentSucceeded(UUID eventId, PaymentMessages.PaymentSucceeded event) {
        Optional<Order> found = load(PAYMENT_EVENTS_CONSUMER, eventId, event.orderId());
        if (found.isEmpty()) {
            return;
        }
        Order order = found.get();
        if (order.isIn(OrderStatus.PENDING_PAYMENT, SagaStep.PAYMENT_REQUESTED)) {
            Order paid = order.paymentSucceeded(event.paymentId(), now());
            if (orders.saveTransition(order, paid)) {
                send(inventoryCommand(
                        paid,
                        InventoryMessages.ConfirmReservation.TYPE,
                        new InventoryMessages.ConfirmReservation(
                                paid.reservationId().value(), paid.id().value()),
                        eventId));
            }
        } else if (order.status() == OrderStatus.CANCELLED) {
            // The payment arrived after the saga had already given up (timeout): the money goes back.
            send(paymentCommand(
                    order,
                    PaymentMessages.RefundPayment.TYPE,
                    new PaymentMessages.RefundPayment(
                            order.id().value(), "Order was cancelled before payment completed"),
                    eventId));
            observer.refundRequested(SagaObserver.Refund.LATE_PAYMENT);
        }
        // Any other state: a duplicate from a different event id (for example a replay); nothing to do.
    }

    /** {@code PaymentFailed} and {@code PaymentTimedOut} both end the checkout. */
    @Transactional
    public void onPaymentFailed(UUID eventId, UUID orderId, String reason) {
        load(PAYMENT_EVENTS_CONSUMER, eventId, orderId)
                .filter(order -> order.isIn(OrderStatus.PENDING_PAYMENT, SagaStep.PAYMENT_REQUESTED))
                .ifPresent(order -> cancel(order, reason, eventId.toString()));
    }

    @Transactional
    public void onPaymentRefunded(UUID eventId, PaymentMessages.PaymentRefunded event) {
        Optional<Order> found = load(PAYMENT_EVENTS_CONSUMER, eventId, event.orderId());
        if (found.isEmpty() || !found.get().isIn(OrderStatus.REFUND_PENDING, SagaStep.REFUNDING)) {
            return; // a late-payment refund of a cancelled order needs no order change
        }
        Order order = found.get();
        Order refunded = order.refunded(now());
        if (orders.saveTransition(order, refunded)) {
            send(orderEvent(
                    refunded,
                    OrderMessages.OrderRefunded.TYPE,
                    new OrderMessages.OrderRefunded(
                            refunded.id().value(), refunded.userId().value(), event.amount(), event.currency()),
                    eventId));
        }
    }

    @Transactional
    public void onReservationConfirmed(UUID eventId, InventoryMessages.ReservationConfirmed event) {
        load(INVENTORY_EVENTS_CONSUMER, eventId, event.orderId())
                .filter(order -> order.isIn(OrderStatus.PENDING_PAYMENT, SagaStep.CONFIRMING_RESERVATION))
                .ifPresent(order -> {
                    Order confirmed = order.confirm(now());
                    if (orders.saveTransition(order, confirmed)) {
                        send(orderEvent(
                                confirmed,
                                OrderMessages.OrderConfirmed.TYPE,
                                new OrderMessages.OrderConfirmed(
                                        confirmed.id().value(),
                                        confirmed.userId().value(),
                                        confirmed.reservationId().value(),
                                        confirmed.totalAmount().amount().toPlainString(),
                                        confirmed.totalAmount().currency().getCurrencyCode()),
                                eventId));
                        observer.checkoutCompleted();
                    }
                });
    }

    /** Payment succeeded, but the reservation expired or was cancelled meanwhile: refund (spec §16 C, ADR-009). */
    @Transactional
    public void onReservationConfirmationRejected(
            UUID eventId, InventoryMessages.ReservationConfirmationRejected event) {
        load(INVENTORY_EVENTS_CONSUMER, eventId, event.orderId())
                .filter(order -> order.isIn(OrderStatus.PENDING_PAYMENT, SagaStep.CONFIRMING_RESERVATION))
                .ifPresent(order -> {
                    String reason = "Reservation " + event.reservationStatus() + " before it could be confirmed";
                    Order refunding = order.refundPending(reason, now());
                    if (orders.saveTransition(order, refunding)) {
                        send(paymentCommand(
                                refunding,
                                PaymentMessages.RefundPayment.TYPE,
                                new PaymentMessages.RefundPayment(refunding.id().value(), reason),
                                eventId));
                        send(orderEvent(
                                refunding,
                                OrderMessages.OrderRefundPending.TYPE,
                                new OrderMessages.OrderRefundPending(
                                        refunding.id().value(),
                                        refunding.userId().value(),
                                        refunding.reservationId().value(),
                                        reason),
                                eventId));
                        observer.refundRequested(SagaObserver.Refund.forRejectedReservation(event.reservationStatus()));
                    }
                });
    }

    /**
     * Cancels orders whose payment result has not arrived within the payment timeout and releases their stock (spec
     * §16: saga timeout). Only orders still waiting for payment qualify, never orders whose payment already succeeded.
     *
     * @return how many orders were cancelled
     */
    @Transactional
    public int cancelOrdersAwaitingPaymentTooLong(int batchSize) {
        List<Order> stuck = orders.claimOrdersAwaitingPaymentLongerThan(policy.paymentTimeout(), batchSize);
        stuck.forEach(order -> cancel(order, "Payment not completed within " + policy.paymentTimeout(), null));
        return stuck.size();
    }

    private void cancel(Order order, String reason, String causationId) {
        Order cancelled = order.cancel(reason, now());
        if (!orders.saveTransition(order, cancelled)) {
            return;
        }
        String causation = causationId != null ? causationId : order.id().toString();
        send(new OutboundMessage(
                Topics.INVENTORY_COMMANDS,
                InventoryMessages.AGGREGATE_RESERVATION,
                cancelled.reservationId().toString(),
                InventoryMessages.ReleaseReservation.TYPE,
                new InventoryMessages.ReleaseReservation(
                        cancelled.reservationId().value(), cancelled.id().value(), reason),
                cancelled.id().toString(),
                causation));
        send(new OutboundMessage(
                Topics.ORDER_EVENTS,
                OrderMessages.AGGREGATE_ORDER,
                cancelled.id().toString(),
                OrderMessages.OrderCancelled.TYPE,
                new OrderMessages.OrderCancelled(
                        cancelled.id().value(),
                        cancelled.userId().value(),
                        cancelled.reservationId().value(),
                        reason),
                cancelled.id().toString(),
                causation));
        observer.compensated(
                causationId == null
                        ? SagaObserver.Compensation.PAYMENT_TIMEOUT
                        : SagaObserver.Compensation.PAYMENT_FAILED);
    }

    /** Marks the event processed and loads its order; empty for a duplicate delivery or an unknown order. */
    private Optional<Order> load(String consumer, UUID eventId, UUID orderId) {
        if (!inbox.markProcessed(consumer, eventId)) {
            return Optional.empty();
        }
        return orders.findById(OrderId.of(orderId));
    }

    private void send(OutboundMessage message) {
        outbox.publish(message);
    }

    private static OutboundMessage inventoryCommand(Order order, String type, Object payload, UUID causation) {
        return new OutboundMessage(
                Topics.INVENTORY_COMMANDS,
                InventoryMessages.AGGREGATE_RESERVATION,
                order.reservationId().toString(),
                type,
                payload,
                order.id().toString(),
                causation.toString());
    }

    private static OutboundMessage paymentCommand(Order order, String type, Object payload, UUID causation) {
        return new OutboundMessage(
                Topics.PAYMENT_COMMANDS,
                PaymentMessages.AGGREGATE_PAYMENT,
                order.id().toString(),
                type,
                payload,
                order.id().toString(),
                causation.toString());
    }

    private static OutboundMessage orderEvent(Order order, String type, Object payload, UUID causation) {
        return new OutboundMessage(
                Topics.ORDER_EVENTS,
                OrderMessages.AGGREGATE_ORDER,
                order.id().toString(),
                type,
                payload,
                order.id().toString(),
                causation.toString());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
