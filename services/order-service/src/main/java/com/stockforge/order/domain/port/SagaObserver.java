package com.stockforge.order.domain.port;

import java.util.Locale;

/** Business signals of the checkout saga, exported as metrics (spec §25: saga compensations, stuck sagas). */
public interface SagaObserver {

    /** Why the saga undid a checkout (order cancelled, stock released). */
    enum Compensation {
        PAYMENT_FAILED,
        PAYMENT_TIMEOUT
    }

    /**
     * Why a payment had to be refunded. A closed set on purpose: it becomes a metric label, and a label taken verbatim
     * from an event field could grow without bound.
     */
    enum Refund {
        /** The payment succeeded after the order had been cancelled (by the payment timeout). */
        LATE_PAYMENT,
        RESERVATION_EXPIRED,
        RESERVATION_CANCELLED,
        RESERVATION_CONFIRMED,
        RESERVATION_NOT_FOUND,
        RESERVATION_OTHER;

        /** The refund reason for a confirmation that inventory rejected because the reservation was in this state. */
        public static Refund forRejectedReservation(String reservationStatus) {
            String status = reservationStatus == null ? "" : reservationStatus.toUpperCase(Locale.ROOT);
            return switch (status) {
                case "EXPIRED" -> RESERVATION_EXPIRED;
                case "CANCELLED" -> RESERVATION_CANCELLED;
                case "CONFIRMED" -> RESERVATION_CONFIRMED;
                case "NOT_FOUND" -> RESERVATION_NOT_FOUND;
                default -> RESERVATION_OTHER;
            };
        }
    }

    void checkoutCompleted();

    void compensated(Compensation reason);

    /** A payment had to be refunded because its order or reservation was gone (spec §16 C). */
    void refundRequested(Refund reason);

    SagaObserver NONE = new SagaObserver() {
        @Override
        public void checkoutCompleted() {}

        @Override
        public void compensated(Compensation reason) {}

        @Override
        public void refundRequested(Refund reason) {}
    };
}
