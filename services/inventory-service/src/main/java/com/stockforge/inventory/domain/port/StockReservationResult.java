package com.stockforge.inventory.domain.port;

/** Result of a stock reservation attempt by a {@link StockReservationStrategy}. */
public sealed interface StockReservationResult {

    record Success() implements StockReservationResult {}

    record InsufficientStock() implements StockReservationResult {}

    record OptimisticConflict() implements StockReservationResult {}
}
