package com.stockforge.inventory.domain.port;

import com.stockforge.inventory.domain.model.Reservation;

/**
 * Common contract for all inventory reservation concurrency control strategies (spec §14).
 *
 * <p>All implementations honor the same contract and are evaluated against the same concurrency test suite and load
 * benchmarks.
 */
public interface StockReservationStrategy {

    StrategyType type();

    StockReservationResult reserve(Reservation reservation);
}
