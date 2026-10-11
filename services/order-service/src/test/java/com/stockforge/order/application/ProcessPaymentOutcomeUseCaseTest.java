package com.stockforge.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.DomainEvents;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.InventoryClient;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessPaymentOutcomeUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private FakeOrderRepository orderRepo;
    private FakeInventoryClient inventoryClient;
    private RecordingOutbox outbox;
    private Clock clock;
    private ProcessPaymentOutcomeUseCase useCase;

    @BeforeEach
    void setUp() {
        orderRepo = new FakeOrderRepository();
        inventoryClient = new FakeInventoryClient();
        outbox = new RecordingOutbox();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new ProcessPaymentOutcomeUseCase(orderRepo, inventoryClient, outbox, clock);
    }

    private Order createPendingOrder() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        UserId userId = UserId.of(UUID.randomUUID());
        ReservationId resId = ReservationId.of(UUID.randomUUID());
        OrderItem item = new OrderItem(ProductId.of(UUID.randomUUID()), 1, Money.of("99.99", "USD"));
        Order order = Order.create(orderId, userId, resId, List.of(item), NOW);
        orderRepo.insert(order);
        return order;
    }

    @Test
    void happyPath_PaymentSucceeded_ConfirmsReservation_ConfirmsOrder_EmitsOrderConfirmed() {
        Order order = createPendingOrder();
        UUID paymentId = UUID.randomUUID();
        inventoryClient.setConfirmResult(InventoryClient.ConfirmResult.CONFIRMED);

        useCase.handlePaymentSuccess(order.id().value(), paymentId);

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.CONFIRMED);

        assertThat(outbox.events).hasSize(1);
        RecordingOutbox.OutboxEntry event = outbox.events.get(0);
        assertThat(event.eventType()).isEqualTo("OrderConfirmed");
        assertThat(event.payload()).isInstanceOf(DomainEvents.OrderConfirmed.class);
    }

    @Test
    void paymentFailed_ReleasesReservation_CancelsOrder_EmitsOrderCancelled() {
        Order order = createPendingOrder();

        useCase.handlePaymentFailure(order.id().value(), "Insufficient funds");

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryClient.releasedReservations).contains(order.reservationId());

        assertThat(outbox.events).hasSize(1);
        RecordingOutbox.OutboxEntry event = outbox.events.get(0);
        assertThat(event.eventType()).isEqualTo("OrderCancelled");
        DomainEvents.OrderCancelled payload = (DomainEvents.OrderCancelled) event.payload();
        assertThat(payload.reason()).contains("Insufficient funds");
    }

    @Test
    void paymentTimedOut_ReleasesReservation_CancelsOrder_EmitsOrderCancelled() {
        Order order = createPendingOrder();

        useCase.handlePaymentTimedOut(order.id().value());

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryClient.releasedReservations).contains(order.reservationId());
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    void w4_07_PaymentAfterReservationExpiry_TriggersRefundAndCancelsOrder() {
        Order order = createPendingOrder();
        UUID paymentId = UUID.randomUUID();
        // Reservation expired before payment completed!
        inventoryClient.setConfirmResult(InventoryClient.ConfirmResult.EXPIRED_OR_INVALID);

        useCase.handlePaymentSuccess(order.id().value(), paymentId);

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.REFUND_PENDING);

        // Verify compensating action: RefundPaymentCommand published to payment-commands topic!
        assertThat(outbox.events).hasSize(2);
        RecordingOutbox.OutboxEntry refundEntry = outbox.events.stream()
                .filter(e -> "RefundPaymentCommand".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(refundEntry.payload()).isInstanceOf(DomainEvents.RefundPaymentCommand.class);

        RecordingOutbox.OutboxEntry cancelEntry = outbox.events.stream()
                .filter(e -> "OrderCancelled".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelEntry.payload()).isInstanceOf(DomainEvents.OrderCancelled.class);
    }

    @Test
    void paymentRefunded_TransitionsOrderFromRefundPendingToRefunded() {
        Order order = createPendingOrder();
        // Move to REFUND_PENDING
        orderRepo.updateStatusIfCurrent(
                order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.REFUND_PENDING, "expired", NOW);

        useCase.handlePaymentRefunded(order.id().value());

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void sagaTimeout_CancelsStuckOrdersAndReleasesReservations() {
        Order order = createPendingOrder();
        Instant cutoff = NOW.plusSeconds(300); // order created at NOW is older than cutoff

        int count = useCase.handleSagaTimeout(cutoff, 10);

        assertThat(count).isEqualTo(1);
        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryClient.releasedReservations).contains(order.reservationId());
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    void duplicatePaymentSuccess_IsIdempotent() {
        Order order = createPendingOrder();
        UUID paymentId = UUID.randomUUID();
        inventoryClient.setConfirmResult(InventoryClient.ConfirmResult.CONFIRMED);

        useCase.handlePaymentSuccess(order.id().value(), paymentId);
        useCase.handlePaymentSuccess(order.id().value(), paymentId);

        Order updated = orderRepo.findById(order.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outbox.events).hasSize(1); // Only 1 event emitted
    }

    // --- Fakes ---

    private static class FakeInventoryClient implements InventoryClient {
        private ConfirmResult confirmResult = ConfirmResult.CONFIRMED;
        final List<ReservationId> releasedReservations = new ArrayList<>();

        void setConfirmResult(ConfirmResult result) {
            this.confirmResult = result;
        }

        @Override
        public ConfirmResult confirmReservation(ReservationId reservationId) {
            return confirmResult;
        }

        @Override
        public void releaseReservation(ReservationId reservationId, String reason) {
            releasedReservations.add(reservationId);
        }
    }

    private static class FakeOrderRepository implements OrderRepository {
        private final Map<OrderId, Order> orders = new HashMap<>();

        @Override
        public void insert(Order order) {
            orders.put(order.id(), order);
        }

        @Override
        public Optional<Order> findById(OrderId id) {
            return Optional.ofNullable(orders.get(id));
        }

        @Override
        public Optional<Order> findByReservationId(ReservationId reservationId) {
            return orders.values().stream()
                    .filter(o -> o.reservationId().equals(reservationId))
                    .findFirst();
        }

        @Override
        public List<Order> findByUserId(UserId userId, int limit) {
            return orders.values().stream()
                    .filter(o -> o.userId().equals(userId))
                    .toList();
        }

        @Override
        public boolean updateStatusIfCurrent(
                OrderId id, OrderStatus currentStatus, OrderStatus newStatus, String reason, Instant now) {
            Order o = orders.get(id);
            if (o != null && o.status() == currentStatus) {
                Order next = switch (newStatus) {
                    case CONFIRMED -> o.confirm(now, reason);
                    case CANCELLED -> o.cancel(now, reason);
                    case REFUND_PENDING -> o.refundPending(now, reason);
                    case REFUNDED -> o.refund(now, reason);
                    default -> throw new IllegalArgumentException("Unsupported transition: " + newStatus);
                };
                orders.put(id, next);
                return true;
            }
            return false;
        }

        @Override
        public List<Order> findStuckPendingOrders(Instant createdBefore, int limit) {
            return orders.values().stream()
                    .filter(o -> o.status() == OrderStatus.PENDING_PAYMENT
                            && o.createdAt().isBefore(createdBefore))
                    .limit(limit)
                    .toList();
        }
    }

    private static class RecordingOutbox implements OutboxPublisher {
        record OutboxEntry(
                String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {}

        final List<OutboxEntry> events = new ArrayList<>();

        @Override
        public void publish(
                String aggregateType, String aggregateId, String eventType, Object payload, String correlationId) {
            events.add(new OutboxEntry(aggregateType, aggregateId, eventType, payload, correlationId));
        }
    }
}
