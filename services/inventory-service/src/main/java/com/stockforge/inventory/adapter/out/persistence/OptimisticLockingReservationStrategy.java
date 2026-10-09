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
import org.springframework.stereotype.Component;

/**
 * Optimistic locking reservation strategy with application retries (spec §14 B).
 *
 * <p>Reads the current version, then attempts to update with {@code WHERE version = :version}. On version mismatch,
 * retries with jittered exponential backoff up to {@code MAX_RETRIES}.
 */
@Component
class OptimisticLockingReservationStrategy implements StockReservationStrategy {

    static final int MAX_RETRIES = 5;

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;

    OptimisticLockingReservationStrategy(InventoryRepository inventories, ReservationRepository reservations) {
        this.inventories = inventories;
        this.reservations = reservations;
    }

    @Override
    public StrategyType type() {
        return StrategyType.OPTIMISTIC_LOCKING;
    }

    @Override
    public StockReservationResult reserve(Reservation reservation) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
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
            if (attempt < MAX_RETRIES) {
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
