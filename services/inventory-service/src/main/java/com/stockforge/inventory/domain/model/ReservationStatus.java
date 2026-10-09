package com.stockforge.inventory.domain.model;

/**
 * State of a reservation along its lifecycle (spec §16 A).
 *
 * <pre>
 *            reserve
 *   (none) ----------> PENDING ---- payment confirmed -------> CONFIRMED
 *                         |
 *                         +-------- user cancel / payment failed -> CANCELLED
 *                         |
 *                         +-------- expires_at passed -----------> EXPIRED
 * </pre>
 *
 * Terminal states (CONFIRMED, CANCELLED, EXPIRED) are final.
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return this == PENDING && target != null && target != PENDING;
    }
}
