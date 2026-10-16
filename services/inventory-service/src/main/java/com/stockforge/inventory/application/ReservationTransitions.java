package com.stockforge.inventory.application;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.Topics;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.port.InventoryRepository;
import com.stockforge.inventory.domain.port.OutboxPublisher;
import com.stockforge.inventory.domain.port.OutboxPublisher.OutboundMessage;
import com.stockforge.inventory.domain.port.ReservationRepository;
import com.stockforge.inventory.domain.port.UserPurchaseLimitRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The state changes that end a PENDING reservation, shared by user cancellation, saga compensation, saga confirmation
 * and expiry so that each one moves the stock, the purchase-limit counter and the outbox identically (spec §16 A).
 *
 * <p>Every method is called inside the caller's transaction and starts with the conditional status update, which is
 * what decides a race between two transitions: the loser gets {@code false} and changes nothing.
 */
final class ReservationTransitions {

    private final ReservationRepository reservations;
    private final InventoryRepository inventories;
    private final UserPurchaseLimitRepository purchaseLimits;
    private final OutboxPublisher outbox;

    ReservationTransitions(
            ReservationRepository reservations,
            InventoryRepository inventories,
            UserPurchaseLimitRepository purchaseLimits,
            OutboxPublisher outbox) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventories = Objects.requireNonNull(inventories, "inventories must not be null");
        this.purchaseLimits = Objects.requireNonNull(purchaseLimits, "purchaseLimits must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    }

    /** PENDING -> CANCELLED: reserved stock returns to available and to the user's allowance. */
    boolean release(Reservation reservation, String reason, UUID orderId, String causationId, Instant now) {
        if (!reservations.updateStatusIfCurrent(
                reservation.id(), ReservationStatus.PENDING, ReservationStatus.CANCELLED, now)) {
            return false;
        }
        returnStock(reservation, now);
        String id = reservation.id().toString();
        outbox.publish(new OutboundMessage(
                Topics.INVENTORY_EVENTS,
                InventoryMessages.AGGREGATE_RESERVATION,
                id,
                InventoryMessages.ReservationReleased.TYPE,
                new InventoryMessages.ReservationReleased(
                        reservation.id().value(),
                        reservation.userId().value(),
                        reservation.productId().value(),
                        reservation.quantity(),
                        reason,
                        orderId),
                orderId != null ? orderId.toString() : id,
                causationId));
        return true;
    }

    /**
     * PENDING -> EXPIRED for a claimed batch (spec §21). Locks are taken in phases with a global order: the claimed
     * reservation rows (already locked), then every limit row sorted by product and user, then every inventory row
     * sorted by product. A per-reservation loop would interleave limit and inventory locks and could deadlock with a
     * concurrent reservation of the same product; aggregating per product also needs fewer UPDATEs on the hot row.
     *
     * @return the reservations that expired; others lost a race against confirmation or cancellation
     */
    List<Reservation> expireAll(List<Reservation> claimed, Instant now) {
        List<Reservation> expired = claimed.stream()
                .filter(reservation -> reservations.updateStatusIfCurrent(
                        reservation.id(), ReservationStatus.PENDING, ReservationStatus.EXPIRED, now))
                .sorted(Comparator.comparing((Reservation reservation) ->
                                reservation.productId().value())
                        .thenComparing(reservation -> reservation.userId().value()))
                .toList();

        expired.forEach(reservation ->
                purchaseLimits.release(reservation.userId(), reservation.productId(), reservation.quantity(), now));

        Map<UUID, Integer> unitsPerProduct = expired.stream()
                .collect(Collectors.groupingBy(
                        reservation -> reservation.productId().value(),
                        TreeMap::new,
                        Collectors.summingInt(Reservation::quantity)));
        unitsPerProduct.forEach((productId, units) -> {
            if (!inventories.releaseReservedStock(ProductId.of(productId), units, now)) {
                throw new IllegalStateException(
                        "Inventory of product " + productId + " does not hold " + units + " expiring reserved units");
            }
        });

        for (Reservation reservation : expired) {
            String id = reservation.id().toString();
            outbox.publish(new OutboundMessage(
                    Topics.INVENTORY_EVENTS,
                    InventoryMessages.AGGREGATE_RESERVATION,
                    id,
                    InventoryMessages.ReservationExpired.TYPE,
                    new InventoryMessages.ReservationExpired(
                            reservation.id().value(),
                            reservation.userId().value(),
                            reservation.productId().value(),
                            reservation.quantity()),
                    id,
                    id));
        }
        return expired;
    }

    /**
     * PENDING -> CONFIRMED: reserved stock becomes sold (saga step 4, the point of no return). The units stay counted
     * against the user's purchase limit, because the user now owns them.
     */
    boolean confirm(Reservation reservation, UUID orderId, String causationId, Instant now) {
        if (!reservations.updateStatusIfCurrent(
                reservation.id(), ReservationStatus.PENDING, ReservationStatus.CONFIRMED, now)) {
            return false;
        }
        requireApplied(
                inventories.confirmReservedStock(reservation.productId(), reservation.quantity(), now), reservation);
        outbox.publish(new OutboundMessage(
                Topics.INVENTORY_EVENTS,
                InventoryMessages.AGGREGATE_RESERVATION,
                reservation.id().toString(),
                InventoryMessages.ReservationConfirmed.TYPE,
                new InventoryMessages.ReservationConfirmed(
                        reservation.id().value(),
                        orderId,
                        reservation.productId().value(),
                        reservation.quantity()),
                orderId.toString(),
                causationId));
        return true;
    }

    private void returnStock(Reservation reservation, Instant now) {
        // Limit row before inventory row: the lock order every flow follows (see ReserveStockUseCase).
        purchaseLimits.release(reservation.userId(), reservation.productId(), reservation.quantity(), now);
        requireApplied(
                inventories.releaseReservedStock(reservation.productId(), reservation.quantity(), now), reservation);
    }

    /**
     * A PENDING reservation always has its units in the reserved bucket. If the conditional update finds fewer, the
     * invariant is already broken: fail and roll back rather than compound the damage.
     */
    private static void requireApplied(boolean applied, Reservation reservation) {
        if (!applied) {
            throw new IllegalStateException("Inventory of product " + reservation.productId()
                    + " does not hold the reserved units of reservation " + reservation.id());
        }
    }
}
