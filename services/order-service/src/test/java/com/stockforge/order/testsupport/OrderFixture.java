package com.stockforge.order.testsupport;

import com.stockforge.order.application.CheckoutPolicy;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.SagaStep;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import com.stockforge.order.domain.port.InventoryCatalog;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import com.stockforge.order.domain.port.ProcessedMessages;
import com.stockforge.order.domain.port.SagaObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory adapters of the order service, for use-case tests without Spring, Kafka or a database. */
public final class OrderFixture {

    public static final Instant START = Instant.parse("2026-10-08T10:15:30Z");
    public static final CheckoutPolicy POLICY = new CheckoutPolicy(Duration.ofMinutes(2), Duration.ofHours(24));

    public final MutableClock clock = new MutableClock(START);
    public final Orders orders = new Orders(clock);
    public final Keys idempotencyKeys = new Keys();
    public final Outbox outbox = new Outbox();
    public final Inbox inbox = new Inbox();
    public final Inventory inventory = new Inventory();
    public final Observer observer = new Observer();
    public final IdGenerator ids = new Ids();

    /** A clock tests advance explicitly. */
    public static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        public synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class Ids implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1);

        @Override
        public OrderId newOrderId() {
            return OrderId.of(new UUID(0xAL, next.getAndIncrement()));
        }
    }

    /** Behaves like the JDBC repository: unique reservation, conditional transitions, database-time timeouts. */
    public static final class Orders implements OrderRepository {
        private final Map<OrderId, Order> rows = new HashMap<>();
        private final Clock clock;

        Orders(Clock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized boolean insertIfReservationUnordered(Order order) {
            if (rows.values().stream().anyMatch(o -> o.reservationId().equals(order.reservationId()))) {
                return false;
            }
            rows.put(order.id(), order);
            return true;
        }

        @Override
        public synchronized Optional<Order> findById(OrderId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized boolean saveTransition(Order before, Order after) {
            Order current = rows.get(before.id());
            if (current == null || !current.isIn(before.status(), before.sagaStep())) {
                return false;
            }
            rows.put(after.id(), after);
            return true;
        }

        @Override
        public synchronized List<Order> findByUser(UserId userId, Optional<PageKey> after, int limit) {
            return rows.values().stream()
                    .filter(o -> o.userId().equals(userId))
                    .sorted(Comparator.comparing(Order::createdAt).reversed().thenComparing(o -> o.id().value()))
                    .filter(o -> after.isEmpty()
                            || o.createdAt().isBefore(after.get().createdAt())
                            || (o.createdAt().equals(after.get().createdAt())
                                    && o.id().value().compareTo(after.get().orderId()) > 0))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized List<Order> claimOrdersAwaitingPaymentLongerThan(Duration timeout, int limit) {
            Instant cutoff = clock.instant().minus(timeout);
            return rows.values().stream()
                    .filter(o -> o.sagaStep() == SagaStep.PAYMENT_REQUESTED
                            && o.createdAt().isBefore(cutoff))
                    .sorted(Comparator.comparing(Order::createdAt))
                    .limit(limit)
                    .toList();
        }

        public synchronized Order only() {
            if (rows.size() != 1) {
                throw new AssertionError("expected exactly one order, found " + rows.size());
            }
            return rows.values().iterator().next();
        }

        public synchronized OrderStatus statusOf(OrderId id) {
            return rows.get(id).status();
        }
    }

    public static final class Keys implements IdempotencyKeyRepository {
        private final Map<String, IdempotencyRecord> rows = new HashMap<>();

        @Override
        public synchronized boolean tryAcquire(
                String key, UserId userId, String requestHash, Instant now, Instant expiresAt) {
            return rows.putIfAbsent(userId + "/" + key, new IdempotencyRecord(key, userId, requestHash, null, null))
                    == null;
        }

        @Override
        public synchronized Optional<IdempotencyRecord> find(String key, UserId userId) {
            return Optional.ofNullable(rows.get(userId + "/" + key));
        }

        @Override
        public synchronized void complete(String key, UserId userId, int responseStatus, String response) {
            IdempotencyRecord current = rows.get(userId + "/" + key);
            rows.put(
                    userId + "/" + key,
                    new IdempotencyRecord(key, userId, current.requestHash(), responseStatus, response));
        }

        @Override
        public int deleteExpired(Instant now, int batchSize) {
            return 0;
        }
    }

    public static final class Outbox implements OutboxPublisher {
        private final List<OutboundMessage> messages = new ArrayList<>();

        @Override
        public synchronized void publish(OutboundMessage message) {
            messages.add(message);
        }

        public synchronized List<String> eventTypes() {
            return messages.stream().map(OutboundMessage::eventType).toList();
        }

        public synchronized List<OutboundMessage> messages() {
            return List.copyOf(messages);
        }

        public synchronized <T> List<T> payloads(Class<T> type) {
            return messages.stream()
                    .map(OutboundMessage::payload)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }

        public synchronized void clear() {
            messages.clear();
        }
    }

    public static final class Inbox implements ProcessedMessages {
        private final Set<String> seen = new HashSet<>();

        @Override
        public synchronized boolean markProcessed(String consumer, UUID eventId) {
            return seen.add(consumer + "/" + eventId);
        }
    }

    /** Stands in for the Inventory service; counts calls so tests can prove a replay made none. */
    public static final class Inventory implements InventoryCatalog {
        private final Map<ReservationId, ReservationSnapshot> reservations = new HashMap<>();
        private final Map<ProductId, Money> prices = new HashMap<>();
        private int calls;

        public void reservation(ReservationSnapshot snapshot, Money price) {
            reservations.put(snapshot.id(), snapshot);
            prices.put(snapshot.productId(), price);
        }

        @Override
        public synchronized Optional<ReservationSnapshot> findReservation(ReservationId id, UserId owner) {
            calls++;
            return Optional.ofNullable(reservations.get(id))
                    .filter(r -> r.owner().equals(owner));
        }

        @Override
        public synchronized Optional<Money> findPrice(ProductId productId) {
            calls++;
            return Optional.ofNullable(prices.get(productId));
        }

        public synchronized int calls() {
            return calls;
        }
    }

    public static final class Observer implements SagaObserver {
        public final List<String> signals = new ArrayList<>();

        @Override
        public void checkoutCompleted() {
            signals.add("completed");
        }

        @Override
        public void compensated(Compensation reason) {
            signals.add("compensated:" + reason);
        }

        @Override
        public void refundRequested(Refund reason) {
            signals.add("refund:" + reason);
        }
    }
}
