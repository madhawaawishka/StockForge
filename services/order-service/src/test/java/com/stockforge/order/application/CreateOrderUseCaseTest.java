package com.stockforge.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
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

class CreateOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final OrderId ORDER_ID = OrderId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-4a0b-7c09-8d21-0a1b2c3d4e5f"));

    private FakeOrderRepository orderRepo;
    private FakeIdempotencyRepo idempRepo;
    private RecordingOutbox outbox;
    private Clock clock;
    private CreateOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        orderRepo = new FakeOrderRepository();
        idempRepo = new FakeIdempotencyRepo();
        outbox = new RecordingOutbox();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        IdGenerator idGenerator = () -> ORDER_ID;

        useCase = new CreateOrderUseCase(orderRepo, idempRepo, outbox, idGenerator, clock);
    }

    @Test
    void createsOrderAndEmitsPaymentCommandToOutbox() {
        OrderItem item = new OrderItem(PRODUCT_ID, 2, Money.of("50.00", "USD"));
        CreateOrderCommand command = new CreateOrderCommand("key-1", USER_ID, RESERVATION_ID, List.of(item));

        CreateOrderResult result = useCase.execute(command);

        assertThat(result).isInstanceOf(CreateOrderResult.Created.class);
        Order created = ((CreateOrderResult.Created) result).order();
        assertThat(created.id()).isEqualTo(ORDER_ID);
        assertThat(created.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(created.totalAmount()).isEqualTo(Money.of("100.00", "USD"));

        // Saved in repository
        assertThat(orderRepo.findById(ORDER_ID)).isPresent();

        // Outbox event published
        assertThat(outbox.events).hasSize(1);
        assertThat(outbox.events.getFirst().eventType()).isEqualTo("RequestPaymentCommand");

        // Idempotency completed
        assertThat(idempRepo.find("key-1", USER_ID)).isPresent();
        assertThat(idempRepo.find("key-1", USER_ID).get().isCompleted()).isTrue();
    }

    @Test
    void returnsIdempotentReplayWhenRetriedWithSameKeyAndPayload() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("99.99", "USD"));
        CreateOrderCommand command = new CreateOrderCommand("key-replay", USER_ID, RESERVATION_ID, List.of(item));

        useCase.execute(command);

        // Replay
        CreateOrderResult replay = useCase.execute(command);
        assertThat(replay).isInstanceOf(CreateOrderResult.IdempotentReplay.class);
        assertThat(((CreateOrderResult.IdempotentReplay) replay).order().id()).isEqualTo(ORDER_ID);
    }

    @Test
    void rejectsDuplicateOrderForSameReservationId() {
        OrderItem item = new OrderItem(PRODUCT_ID, 1, Money.of("99.99", "USD"));
        CreateOrderCommand command1 = new CreateOrderCommand("key-1", USER_ID, RESERVATION_ID, List.of(item));
        CreateOrderCommand command2 = new CreateOrderCommand("key-2", USER_ID, RESERVATION_ID, List.of(item));

        useCase.execute(command1);

        CreateOrderResult result = useCase.execute(command2);
        assertThat(result).isInstanceOf(CreateOrderResult.ReservationAlreadyOrdered.class);
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
                orders.put(id, o.confirm(now, reason));
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

    private static class FakeIdempotencyRepo implements IdempotencyKeyRepository {
        private final Map<String, IdempotencyRecord> map = new HashMap<>();

        @Override
        public boolean tryAcquire(String key, UserId userId, String requestHash, Instant now, Instant expiresAt) {
            String k = userId + ":" + key;
            if (map.containsKey(k)) return false;
            map.put(k, new IdempotencyRecord(key, userId, requestHash, "IN_PROGRESS", null, null, now, expiresAt));
            return true;
        }

        @Override
        public void complete(String key, UserId userId, int responseStatus, String responsePayload, Instant now) {
            String k = userId + ":" + key;
            IdempotencyRecord prev = map.get(k);
            if (prev != null) {
                map.put(
                        k,
                        new IdempotencyRecord(
                                key,
                                userId,
                                prev.requestHash(),
                                "COMPLETED",
                                responseStatus,
                                responsePayload,
                                prev.createdAt(),
                                prev.expiresAt()));
            }
        }

        @Override
        public Optional<IdempotencyRecord> find(String key, UserId userId) {
            return Optional.ofNullable(map.get(userId + ":" + key));
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
