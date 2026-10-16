package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optimistic locking reservation strategy with application retries (spec §14 B).
 *
 * <p>Reads the current version, then attempts to update with {@code WHERE version = :version}. On version mismatch,
 * retries with jittered exponential backoff up to the configured number of attempts.
 */
class OptimisticLockingReservationStrategy implements StockReservationStrategy {

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;
    private final int maxAttempts;

    OptimisticLockingReservationStrategy(
            InventoryRepository inventories, ReservationRepository reservations, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.inventories = inventories;
        this.reservations = reservations;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public StrategyType type() {
        return StrategyType.OPTIMISTIC_LOCKING;
    }

    @Override
    public StockReservationResult reserve(Reservation reservation) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<Inventory> current = inventories.findByProductId(reservation.productId());
            if (current.isEmpty() || current.get().availableQuantity() < reservation.quantity()) {
                return new StockReservationResult.InsufficientStock();
            }

            boolean updated = inventories.updateVersionedStock(
                    reservation.productId(),
                    reservation.quantity(),
                    current.get().version(),
                    reservation.createdAt());

            if (updated) {
                reservations.insert(reservation);
                return new StockReservationResult.Success();
            }

            // Conflict detected: backoff with random jitter before retrying
            if (attempt < maxAttempts) {
                try {
                    int backoffMs = (attempt * 5) + ThreadLocalRandom.current().nextInt(5);
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new StockReservationResult.OptimisticConflict();
                }
            }
        }
        return new StockReservationResult.OptimisticConflict();
    }
}
