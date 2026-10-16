package com.stockforge.inventory.application;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.Topics;
import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductStatus;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.OutboxPublisher.OutboundMessage;
import com.stockforge.inventory.domain.port.ProductRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves stock for a user (spec §14, §15, §19), as one transaction:
 *
 * <ol>
 *   <li>claim the idempotency key, or replay the stored outcome of an earlier request with the same key;
 *   <li>count the units against the user's purchase limit (counter row, prevents write skew);
 *   <li>move the units from available to reserved with the configured {@link StockReservationStrategy};
 *   <li>append {@code ReservationCreated} to the outbox.
 * </ol>
 *
 * <p>Deterministic outcomes (reserved, sold out, limit exceeded) are stored with the key and replayed to a retry, so a
 * client never sees two different answers for one key. A transient outcome (optimistic conflict) forgets the key so the
 * client can simply retry.
 *
 * <p>Lock order is fixed: idempotency row, then the user's limit row, then the product's inventory row. Cancellation
 * and expiry also take the limit row before the inventory row, so these flows cannot deadlock each other.
 */
public class ReserveStockUseCase {

    static final int STATUS_CREATED = 201;
    static final int STATUS_INSUFFICIENT_STOCK = 409;
    static final int STATUS_LIMIT_EXCEEDED = 422;

    private final ProductRepository products;
    private final StockReservationStrategy strategy;
    private final ReservationRepository reservations;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final UserPurchaseLimitRepository purchaseLimits;
    private final OutboxPublisher outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final ReservationPolicy policy;

    public ReserveStockUseCase(
            ProductRepository products,
            StockReservationStrategy strategy,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            IdGenerator ids,
            Clock clock,
            ReservationPolicy policy) {
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.purchaseLimits = Objects.requireNonNull(purchaseLimits, "purchaseLimits must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Transactional
    public ReserveStockResult execute(ReserveStockCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Optional<Product> product = products.findById(command.productId());
        // Unknown and inactive products are not reservable; neither outcome is stored, since the catalog may change.
        if (product.isEmpty() || product.get().status() != ProductStatus.ACTIVE) {
            return new ReserveStockResult.ProductNotFound(command.productId());
        }

        String requestHash = hash(command);
        boolean claimed = idempotencyKeys.tryAcquire(
                command.idempotencyKey(), command.userId(), requestHash, now, now.plus(policy.idempotencyKeyTtl()));
        if (!claimed) {
            return replay(command, requestHash);
        }

        if (!purchaseLimits.tryAcquire(
                command.userId(), command.productId(), command.quantity(), policy.purchaseLimit(), now)) {
            idempotencyKeys.complete(command.idempotencyKey(), command.userId(), STATUS_LIMIT_EXCEEDED, null);
            return new ReserveStockResult.PurchaseLimitExceeded(command.userId(), policy.purchaseLimit());
        }

        Reservation reservation = Reservation.create(
                ReservationId.of(ids.newId()),
                command.userId(),
                command.productId(),
                command.quantity(),
                now.plus(policy.reservationTtl()),
                now);

        return switch (strategy.reserve(reservation)) {
            case StockReservationResult.Success success -> {
                outbox.publish(reservationCreated(reservation));
                idempotencyKeys.complete(
                        command.idempotencyKey(),
                        command.userId(),
                        STATUS_CREATED,
                        reservation.id().toString());
                yield new ReserveStockResult.Reserved(reservation, false);
            }
            case StockReservationResult.InsufficientStock insufficient -> {
                purchaseLimits.release(command.userId(), command.productId(), command.quantity(), now);
                idempotencyKeys.complete(command.idempotencyKey(), command.userId(), STATUS_INSUFFICIENT_STOCK, null);
                yield new ReserveStockResult.InsufficientStock(command.productId(), command.quantity());
            }
            case StockReservationResult.OptimisticConflict conflict -> {
                purchaseLimits.release(command.userId(), command.productId(), command.quantity(), now);
                idempotencyKeys.release(command.idempotencyKey(), command.userId());
                yield new ReserveStockResult.OptimisticConflict(command.productId());
            }
        };
    }

    private ReserveStockResult replay(ReserveStockCommand command, String requestHash) {
        // The key exists and its transaction has committed: the insert of the original request blocked until then.
        IdempotencyRecord existing = idempotencyKeys
                .findByKeyAndUser(command.idempotencyKey(), command.userId())
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished while being replayed"));
        if (!existing.requestHash().equals(requestHash)) {
            return new ReserveStockResult.IdempotencyKeyReused(command.idempotencyKey());
        }
        Integer status = existing.responseStatus();
        if (status == null) {
            throw new IllegalStateException("Idempotency key " + command.idempotencyKey() + " has no stored outcome");
        }
        return switch (status) {
            case STATUS_CREATED -> {
                ReservationId id = ReservationId.of(existing.responsePayload());
                Reservation reservation = reservations
                        .findById(id)
                        .orElseThrow(() -> new IllegalStateException("Replayed reservation " + id + " is missing"));
                yield new ReserveStockResult.Reserved(reservation, true);
            }
            case STATUS_INSUFFICIENT_STOCK ->
                new ReserveStockResult.InsufficientStock(command.productId(), command.quantity());
            case STATUS_LIMIT_EXCEEDED ->
                new ReserveStockResult.PurchaseLimitExceeded(command.userId(), policy.purchaseLimit());
            default -> throw new IllegalStateException("Unknown stored outcome " + status);
        };
    }

    private static OutboundMessage reservationCreated(Reservation reservation) {
        String id = reservation.id().toString();
        return new OutboundMessage(
                Topics.INVENTORY_EVENTS,
                InventoryMessages.AGGREGATE_RESERVATION,
                id,
                InventoryMessages.ReservationCreated.TYPE,
                new InventoryMessages.ReservationCreated(
                        reservation.id().value(),
                        reservation.userId().value(),
                        reservation.productId().value(),
                        reservation.quantity(),
                        reservation.expiresAt()),
                id,
                id);
    }

    /** The key is scoped by user already, so the hash covers the remaining request fields. */
    private static String hash(ReserveStockCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("reserve:" + command.productId() + ":" + command.quantity())
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
