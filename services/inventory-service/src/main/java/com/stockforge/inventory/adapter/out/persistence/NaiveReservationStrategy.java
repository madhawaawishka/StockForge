package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Naive reservation strategy (spec §14, W2-03).
 *
 * <p><strong>TEST-ONLY / BENCHMARK-ONLY:</strong> Demonstrates the classic lost-update race condition. It reads the
 * stock level into application memory, verifies it, pauses briefly (simulating non-atomic processing), and writes back
 * without a version check or a {@code WHERE available_quantity >= :qty} guard. Under concurrent load, multiple threads
 * read the same stock and overwrite each other, causing overselling.
 */
@Component
class NaiveReservationStrategy implements StockReservationStrategy {

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;

    NaiveReservationStrategy(InventoryRepository inventories, ReservationRepository reservations) {
        this.inventories = inventories;
        this.reservations = reservations;
    }

    @Override
    public StrategyType type() {
        return StrategyType.NAIVE;
    }

    @Override
    public StockReservationResult reserve(Reservation reservation) {
        Optional<Inventory> current = inventories.findByProductId(reservation.productId());
        if (current.isEmpty() || current.get().availableQuantity() < reservation.quantity()) {
            return new StockReservationResult.InsufficientStock();
        }

        Inventory inv = current.get();
        int newAvailable = inv.availableQuantity() - reservation.quantity();
        int newReserved = inv.reservedQuantity() + reservation.quantity();

        // Deliberate sleep to widen the concurrent interleaving window
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        inventories.updateUnconditional(reservation.productId(), newAvailable, newReserved, reservation.createdAt());
        reservations.insert(reservation);
        return new StockReservationResult.Success();
    }
}
