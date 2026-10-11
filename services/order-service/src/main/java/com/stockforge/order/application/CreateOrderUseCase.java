package com.stockforge.order.application;

import com.stockforge.events.DomainEvents;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.IdempotencyKeyRepository;
import com.stockforge.order.domain.port.OrderRepository;
import com.stockforge.order.domain.port.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an order against an active reservation with strict idempotency and transactional outbox emission (spec §16,
 * §19, §20).
 */
public class CreateOrderUseCase {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final OrderRepository orders;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxPublisher outbox;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public CreateOrderUseCase(
            OrderRepository orders,
            IdempotencyKeyRepository idempotencyKeys,
            OutboxPublisher outbox,
            IdGenerator idGenerator,
            Clock clock) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public CreateOrderResult execute(CreateOrderCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String requestHash = hashCommand(command);

        // 1. Check idempotency record
        Optional<IdempotencyKeyRepository.IdempotencyRecord> existingKey =
                idempotencyKeys.find(command.idempotencyKey(), command.userId());
        if (existingKey.isPresent()) {
            IdempotencyKeyRepository.IdempotencyRecord record = existingKey.get();
            if (!record.requestHash().equals(requestHash)) {
                return new CreateOrderResult.IdempotencyConflict(
                        "Idempotency key reused with a different request payload");
            }
            if (record.isCompleted() && record.responsePayload() != null) {
                OrderId orderId = OrderId.of(UUID.fromString(record.responsePayload()));
                Optional<Order> order = orders.findById(orderId);
                if (order.isPresent()) {
                    return new CreateOrderResult.IdempotentReplay(order.get());
                }
            }
            return new CreateOrderResult.IdempotencyConflict("Operation with this key is already in progress");
        }

        // 2. Acquire idempotency key
        boolean acquired = idempotencyKeys.tryAcquire(
                command.idempotencyKey(), command.userId(), requestHash, now, now.plus(IDEMPOTENCY_TTL));
        if (!acquired) {
            return new CreateOrderResult.IdempotencyConflict("Failed to acquire idempotency key under concurrency");
        }

        // 3. Invariant: only one order per reservation (spec §13, §16)
        Optional<Order> existingOrder = orders.findByReservationId(command.reservationId());
        if (existingOrder.isPresent()) {
            return new CreateOrderResult.ReservationAlreadyOrdered(command.reservationId());
        }

        // 4. Create Order aggregate
        OrderId newId = idGenerator.newOrderId();
        Order order = Order.create(newId, command.userId(), command.reservationId(), command.items(), now);
        orders.insert(order);

        // 5. Emit RequestPaymentCommand to outbox (initiates Saga Step 2 -> 3)
        DomainEvents.RequestPaymentCommand paymentCommand = new DomainEvents.RequestPaymentCommand(
                order.id().value(),
                order.userId().value(),
                order.totalAmount().amount().toPlainString(),
                order.totalAmount().currency().getCurrencyCode(),
                command.idempotencyKey());

        outbox.publish(
                "Order",
                order.id().value().toString(),
                "RequestPaymentCommand",
                paymentCommand,
                order.id().value().toString());

        // 6. Complete idempotency key
        idempotencyKeys.complete(
                command.idempotencyKey(),
                command.userId(),
                202,
                order.id().value().toString(),
                now);

        return new CreateOrderResult.Created(order);
    }

    private static String hashCommand(CreateOrderCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = command.userId() + ":" + command.reservationId();
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
