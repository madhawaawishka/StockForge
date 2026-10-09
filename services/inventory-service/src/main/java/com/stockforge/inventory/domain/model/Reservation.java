package com.stockforge.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Reservation aggregate (spec §13, §16 A).
 *
 * <p>Enforces the reservation state machine and invariants in pure Java without framework dependencies.
 */
public record Reservation(
        ReservationId id,
        UserId userId,
        ProductId productId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public Reservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (quantity <= 0) {
            throw new DomainValidationException("Quantity must be positive");
        }
        if (expiresAt.isBefore(createdAt)) {
            throw new DomainValidationException("expiresAt must not be before createdAt");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException("updatedAt must not be before createdAt");
        }
    }

    /** Creates a new reservation in PENDING status. */
    public static Reservation create(
            ReservationId id, UserId userId, ProductId productId, int quantity, Instant expiresAt, Instant now) {
        return new Reservation(id, userId, productId, quantity, ReservationStatus.PENDING, expiresAt, now, now);
    }

    /** Transitions PENDING -> CONFIRMED. */
    public Reservation confirm(Instant now) {
        requirePending("confirm");
        return new Reservation(id, userId, productId, quantity, ReservationStatus.CONFIRMED, expiresAt, createdAt, now);
    }

    /** Transitions PENDING -> CANCELLED. */
    public Reservation cancel(Instant now) {
        requirePending("cancel");
        return new Reservation(id, userId, productId, quantity, ReservationStatus.CANCELLED, expiresAt, createdAt, now);
    }

    /** Transitions PENDING -> EXPIRED. */
    public Reservation expire(Instant now) {
        requirePending("expire");
        return new Reservation(id, userId, productId, quantity, ReservationStatus.EXPIRED, expiresAt, createdAt, now);
    }

    public boolean isPending() {
        return status == ReservationStatus.PENDING;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    private void requirePending(String action) {
        if (!status.canTransitionTo(ReservationStatus.CONFIRMED)) { // any transition from non-PENDING is illegal
            throw new DomainValidationException(
                    "Cannot " + action + " reservation in status " + status + "; only PENDING can be transitioned");
        }
    }
}
