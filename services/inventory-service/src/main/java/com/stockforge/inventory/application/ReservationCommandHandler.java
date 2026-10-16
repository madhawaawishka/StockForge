package com.stockforge.inventory.application;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.Topics;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.OutboxPublisher.OutboundMessage;
import com.stockforge.inventory.domain.port.ProcessedMessages;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the saga's commands to reservations (spec §16 B): confirm after payment, release as compensation.
 *
 * <p>Each command is applied exactly once per event id: the inbox row and the state change share one transaction.
 * Commands are also naturally idempotent, because every transition is conditional on the reservation still being
 * PENDING; the inbox additionally keeps a redelivered confirmation from publishing a second reply.
 */
public class ReservationCommandHandler {

    /** Inbox consumer name; one per logical consumer so that replays to other consumers are not suppressed. */
    public static final String CONSUMER = "inventory-reservation-commands";

    private final ProcessedMessages inbox;
    private final ReservationRepository reservations;
    private final ReservationTransitions transitions;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ReservationCommandHandler(
            ProcessedMessages inbox,
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox,
            Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.transitions = new ReservationTransitions(reservations, inventories, purchaseLimits, outbox);
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Confirms the reservation, or replies that it can no longer be confirmed (it expired or was cancelled), so the
     * saga moves the order to the refund path (spec §16 C).
     *
     * @return {@code false} if the command was a duplicate and nothing happened
     */
    @Transactional
    public boolean confirm(UUID eventId, InventoryMessages.ConfirmReservation command) {
        if (!inbox.markProcessed(CONSUMER, eventId)) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String causation = eventId.toString();
        Optional<Reservation> found = reservations.findById(ReservationId.of(command.reservationId()));
        if (found.isPresent() && transitions.confirm(found.get(), command.orderId(), causation, now)) {
            return true;
        }
        // Not PENDING any more (or unknown): report the state that won the race instead of failing the command.
        String status = found.map(reservation -> reservations
                        .findById(reservation.id())
                        .orElseThrow()
                        .status()
                        .name())
                .orElse("NOT_FOUND");
        outbox.publish(new OutboundMessage(
                Topics.INVENTORY_EVENTS,
                InventoryMessages.AGGREGATE_RESERVATION,
                command.reservationId().toString(),
                InventoryMessages.ReservationConfirmationRejected.TYPE,
                new InventoryMessages.ReservationConfirmationRejected(
                        command.reservationId(), command.orderId(), status),
                command.orderId().toString(),
                causation));
        return true;
    }

    /**
     * Compensation: releases the stock of a reservation whose order failed. Releasing a reservation that already
     * expired, was cancelled or (impossible by saga design) confirmed is a no-op, which makes the command safe to
     * repeat.
     *
     * @return {@code false} if the command was a duplicate
     */
    @Transactional
    public boolean release(UUID eventId, InventoryMessages.ReleaseReservation command) {
        if (!inbox.markProcessed(CONSUMER, eventId)) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        reservations
                .findById(ReservationId.of(command.reservationId()))
                .ifPresent(reservation ->
                        transitions.release(reservation, command.reason(), command.orderId(), eventId.toString(), now));
        return true;
    }
}
