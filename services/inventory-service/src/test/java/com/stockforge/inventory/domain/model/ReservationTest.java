package com.stockforge.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));
    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final Instant EXPIRES_AT = NOW.plus(10, ChronoUnit.MINUTES);

    @Test
    void createsReservationInPendingStatus() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);

        assertThat(reservation.id()).isEqualTo(RESERVATION_ID);
        assertThat(reservation.userId()).isEqualTo(USER_ID);
        assertThat(reservation.productId()).isEqualTo(PRODUCT_ID);
        assertThat(reservation.quantity()).isEqualTo(2);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reservation.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(reservation.createdAt()).isEqualTo(NOW);
        assertThat(reservation.updatedAt()).isEqualTo(NOW);
        assertThat(reservation.isPending()).isTrue();
        assertThat(reservation.isExpired(NOW)).isFalse();
    }

    @Test
    void confirmsPendingReservation() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);
        Instant later = NOW.plusSeconds(30);

        Reservation confirmed = reservation.confirm(later);

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.updatedAt()).isEqualTo(later);
        assertThat(confirmed.isPending()).isFalse();
    }

    @Test
    void cancelsPendingReservation() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);
        Instant later = NOW.plusSeconds(15);

        Reservation cancelled = reservation.cancel(later);

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.updatedAt()).isEqualTo(later);
        assertThat(cancelled.isPending()).isFalse();
    }

    @Test
    void expiresPendingReservation() {
        Reservation reservation = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);
        Instant expiryTime = EXPIRES_AT.plusSeconds(1);

        Reservation expired = reservation.expire(expiryTime);

        assertThat(expired.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expired.updatedAt()).isEqualTo(expiryTime);
        assertThat(expired.isPending()).isFalse();
        assertThat(expired.isExpired(expiryTime)).isTrue();
    }

    @Test
    void terminalStatesRejectFurtherTransitions() {
        Reservation pending = Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);
        Instant later = NOW.plusSeconds(60);

        Reservation confirmed = pending.confirm(later);
        Reservation cancelled = pending.cancel(later);
        Reservation expired = pending.expire(later);

        assertThatThrownBy(() -> confirmed.confirm(later.plusSeconds(1)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("only PENDING can be transitioned");
        assertThatThrownBy(() -> confirmed.cancel(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> confirmed.expire(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);

        assertThatThrownBy(() -> cancelled.confirm(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> cancelled.cancel(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);

        assertThatThrownBy(() -> expired.confirm(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> expired.cancel(later.plusSeconds(1))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsZeroOrNegativeQuantity() {
        assertThatThrownBy(() -> Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 0, EXPIRES_AT, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Quantity must be positive");

        assertThatThrownBy(() -> Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, -1, EXPIRES_AT, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Quantity must be positive");
    }

    @Test
    void rejectsExpiryBeforeCreatedAt() {
        Instant before = NOW.minusSeconds(10);
        assertThatThrownBy(() -> Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 1, before, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("expiresAt must not be before createdAt");
    }

    @Test
    void rejectsUpdatedAtBeforeCreatedAt() {
        Instant before = NOW.minusSeconds(1);
        assertThatThrownBy(() -> new Reservation(
                        RESERVATION_ID, USER_ID, PRODUCT_ID, 1, ReservationStatus.PENDING, EXPIRES_AT, NOW, before))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("updatedAt must not be before createdAt");
    }
}
