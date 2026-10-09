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
import org.springframework.transaction.annotation.Transactional;

/**
 * Pessimistic locking reservation strategy (spec §14 A).
 *
 * <p>Acquires an exclusive lock on the inventory row ({@code SELECT ... FOR UPDATE}) before mutating stock. Guarantees
 * zero overselling by serializing concurrent transactions on the row lock.
 */
@Component
class PessimisticLockingReservationStrategy implements StockReservationStrategy {

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;

    PessimisticLockingReservationStrategy(InventoryRepository inventories, ReservationRepository reservations) {
        this.inventories = inventories;
        this.reservations = reservations;
    }

    @Override
    public StrategyType type() {
        return StrategyType.PESSIMISTIC_LOCKING;
    }

    @Transactional
    @Override
    public StockReservationResult reserve(Reservation reservation) {
        Optional<Inventory> locked = inventories.findByProductIdForUpdate(reservation.productId());
        if (locked.isEmpty() || locked.get().availableQuantity() < reservation.quantity()) {
            return new StockReservationResult.InsufficientStock();
        }
        boolean decremented = inventories.decrementAvailableAndIncrementReserved(
                reservation.productId(), reservation.quantity(), reservation.createdAt());
        if (!decremented) {
            return new StockReservationResult.InsufficientStock();
        }
        reservations.insert(reservation);
        return new StockReservationResult.Success();
    }
}
