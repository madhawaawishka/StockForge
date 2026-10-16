package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.model.IdempotencyRecord;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.port.AuditLog;
import com.stockforge.inventory.domain.port.IdempotencyKeyRepository;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ProductCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audited, idempotent stock adjustment (FR-10, spec §19).
 *
 * <p>The change is one conditional UPDATE ({@code available + delta >= 0}), so it never races with concurrent
 * reservations; the cached availability is evicted after the transaction commits (spec §17).
 */
public class AdjustStockUseCase {

    static final int STATUS_ADJUSTED = 200;
    static final int STATUS_INSUFFICIENT_AVAILABLE = 409;

    private final InventoryRepository inventories;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final AuditLog auditLog;
    private final ProductCache cache;
    private final Clock clock;
    private final ReservationPolicy policy;

    public AdjustStockUseCase(
            InventoryRepository inventories,
            IdempotencyKeyRepository idempotencyKeys,
            AuditLog auditLog,
            ProductCache cache,
            Clock clock,
            ReservationPolicy policy) {
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Transactional
    public AdjustStockResult execute(AdjustStockCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Inventory> current = inventories.findByProductId(command.productId());
        if (current.isEmpty()) {
            return new AdjustStockResult.ProductNotFound(command.productId());
        }

        String requestHash = hash(command);
        if (!idempotencyKeys.tryAcquire(
                command.idempotencyKey(), command.actor(), requestHash, now, now.plus(policy.idempotencyKeyTtl()))) {
            return replay(command, requestHash, current.get());
        }

        Optional<Inventory> adjusted = inventories.adjustStock(command.productId(), command.delta(), now);
        if (adjusted.isEmpty()) {
            idempotencyKeys.complete(command.idempotencyKey(), command.actor(), STATUS_INSUFFICIENT_AVAILABLE, null);
            return new AdjustStockResult.InsufficientAvailableStock(command.productId(), command.delta());
        }

        auditLog.record(
                command.actor().toString(),
                "STOCK_ADJUSTED",
                "Inventory",
                command.productId().toString(),
                Map.of(
                        "delta", command.delta(),
                        "reason", command.reason(),
                        "totalAfter", adjusted.get().totalQuantity(),
                        "availableAfter", adjusted.get().availableQuantity()));
        idempotencyKeys.complete(command.idempotencyKey(), command.actor(), STATUS_ADJUSTED, null);
        cache.evictInventory(command.productId());
        return new AdjustStockResult.Adjusted(adjusted.get(), false);
    }

    private AdjustStockResult replay(AdjustStockCommand command, String requestHash, Inventory current) {
        IdempotencyRecord existing = idempotencyKeys
                .findByKeyAndUser(command.idempotencyKey(), command.actor())
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished while being replayed"));
        if (!existing.requestHash().equals(requestHash)) {
            return new AdjustStockResult.IdempotencyKeyReused(command.idempotencyKey());
        }
        Integer status = existing.responseStatus();
        if (status != null && status == STATUS_INSUFFICIENT_AVAILABLE) {
            return new AdjustStockResult.InsufficientAvailableStock(command.productId(), command.delta());
        }
        return new AdjustStockResult.Adjusted(current, true);
    }

    private static String hash(AdjustStockCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("adjust:" + command.productId() + ":" + command.delta() + ":" + command.reason())
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
