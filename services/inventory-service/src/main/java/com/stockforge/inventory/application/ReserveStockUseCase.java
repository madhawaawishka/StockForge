package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.port.IdGenerator;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import com.stockforge.inventory.domain.port.ProductRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves stock atomically using the configured {@link StockReservationStrategy}, enforcing purchase limits and
 * client-scoped idempotency keys (spec §14, §15, §19).
 */
public class ReserveStockUseCase {

    public static final Duration DEFAULT_RESERVATION_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_PURCHASE_LIMIT = 2;

    private final ProductRepository products;
    private final StockReservationStrategy strategy;
    private final ReservationRepository reservations;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final UserPurchaseLimitRepository purchaseLimits;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final int purchaseLimit;
    private final Duration reservationTtl;

    public ReserveStockUseCase(
            ProductRepository products,
            StockReservationStrategy strategy,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            UserPurchaseLimitRepository purchaseLimits,
            IdGenerator idGenerator,
            Clock clock) {
        this(
                products,
                strategy,
                reservations,
                idempotencyKeys,
                purchaseLimits,
                idGenerator,
                clock,
                DEFAULT_PURCHASE_LIMIT,
                DEFAULT_RESERVATION_TTL);
    }

    public ReserveStockUseCase(
            ProductRepository products,
            StockReservationStrategy strategy,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            UserPurchaseLimitRepository purchaseLimits,
            IdGenerator idGenerator,
            Clock clock,
            int purchaseLimit,
            Duration reservationTtl) {
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.purchaseLimits = Objects.requireNonNull(purchaseLimits, "purchaseLimits must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.purchaseLimit = purchaseLimit;
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl must not be null");
    }

    @Transactional
    public ReserveStockResult execute(ReserveStockCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(reservationTtl);

        // 1. Validate product exists
        if (products.findById(command.productId()).isEmpty()) {
            return new ReserveStockResult.ProductNotFound(command.productId());
        }

        // 2. Handle Idempotency
        String requestHash = hashRequest(command);
        Instant idempotencyExpiresAt = now.plus(24, ChronoUnit.HOURS);
        boolean keyAcquired = idempotencyKeys.tryAcquire(
                command.idempotencyKey(), command.userId(), requestHash, now, idempotencyExpiresAt);

        if (!keyAcquired) {
            Optional<IdempotencyRecord> existing =
                    idempotencyKeys.findByKeyAndUser(command.idempotencyKey(), command.userId());
            if (existing.isEmpty() || !existing.get().requestHash().equals(requestHash)) {
                return new ReserveStockResult.IdempotencyConflict(
                        "Idempotency key reused with different request payload");
            }
            if (existing.get().isInProgress()) {
                return new ReserveStockResult.IdempotencyConflict(
                        "A request with this idempotency key is already in progress");
            }
            if (existing.get().isCompleted() && existing.get().responsePayload() != null) {
                ReservationId existingId = ReservationId.of(existing.get().responsePayload());
                Optional<Reservation> replayed = reservations.findById(existingId);
                if (replayed.isPresent()) {
                    return new ReserveStockResult.IdempotentReplay(replayed.get());
                }
            }
            return new ReserveStockResult.IdempotencyConflict(
                    "Idempotency key was previously processed but stored reservation could not be retrieved");
        }

        // 3. Purchase limit check
        boolean limitAcquired = purchaseLimits.tryAcquire(
                command.userId(), command.productId(), command.quantity(), purchaseLimit, now);
        if (!limitAcquired) {
            idempotencyKeys.fail(command.idempotencyKey(), command.userId());
            return new ReserveStockResult.PurchaseLimitExceeded(command.userId(), purchaseLimit);
        }

        // 4. Construct reservation aggregate
        ReservationId id = ReservationId.of(idGenerator.newId());
        Reservation reservation =
                Reservation.create(id, command.userId(), command.productId(), command.quantity(), expiresAt, now);

        // 5. Apply reservation strategy
        StockReservationResult result = strategy.reserve(reservation);
        return switch (result) {
            case StockReservationResult.Success s -> {
                idempotencyKeys.complete(
                        command.idempotencyKey(),
                        command.userId(),
                        201,
                        reservation.id().value().toString());
                yield new ReserveStockResult.Reserved(reservation);
            }
            case StockReservationResult.InsufficientStock i -> {
                purchaseLimits.release(command.userId(), command.productId(), command.quantity(), now);
                idempotencyKeys.fail(command.idempotencyKey(), command.userId());
                yield new ReserveStockResult.InsufficientStock(command.productId(), command.quantity());
            }
            case StockReservationResult.OptimisticConflict o -> {
                purchaseLimits.release(command.userId(), command.productId(), command.quantity(), now);
                idempotencyKeys.fail(command.idempotencyKey(), command.userId());
                yield new ReserveStockResult.OptimisticConflict(command.productId());
            }
        };
    }

    private static String hashRequest(ReserveStockCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = command.productId().value() + ":" + command.quantity();
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
