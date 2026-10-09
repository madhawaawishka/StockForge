package com.stockforge.inventory.adapter.out.persistence;

import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recommended baseline reservation strategy (spec §14 C).
 *
 * <p>Executes a single atomic UPDATE with {@code WHERE available_quantity >= :qty}. Under READ COMMITTED, concurrent
 * updates queue on the row lock and re-evaluate against the newly committed state, guaranteeing zero overselling with
 * zero read-then-write gap and zero application retries.
 */
@org.springframework.context.annotation.Primary
@Component
class AtomicConditionalUpdateReservationStrategy implements StockReservationStrategy {

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;

    AtomicConditionalUpdateReservationStrategy(InventoryRepository inventories, ReservationRepository reservations) {
        this.inventories = inventories;
        this.reservations = reservations;
    }

    @Override
    public StrategyType type() {
        return StrategyType.ATOMIC_CONDITIONAL_UPDATE;
    }

    @Transactional
    @Override
    public StockReservationResult reserve(Reservation reservation) {
        boolean decremented = inventories.decrementAvailableAndIncrementReserved(
                reservation.productId(), reservation.quantity(), reservation.createdAt());
        if (!decremented) {
            return new StockReservationResult.InsufficientStock();
        }
        reservations.insert(reservation);
        return new StockReservationResult.Success();
    }
}
