package com.stockforge.order.application;

import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderItem;
import com.stockforge.order.domain.port.IdGenerator;
import com.stockforge.order.domain.port.InventoryCatalog;
import com.stockforge.order.domain.port.InventoryCatalog.ReservationSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Places the order for a reservation: saga step 2 (spec §16 B).
 *
 * <ol>
 *   <li>A retry with a known idempotency key is answered from the stored outcome, before any remote call.
 *   <li>The reservation and the product price are read from Inventory over gRPC, <b>outside</b> any database
 *       transaction (spec §15: never a network call inside a transaction).
 *   <li>{@link PlaceOrderTransaction} stores the order, its idempotency key and its outbox messages in one short
 *       transaction.
 * </ol>
 *
 * <p>The reservation check is advisory: the reservation may expire between the check and the payment. That race is
 * resolved later by the saga (confirmation rejected, refund), which is why the check does not need to lock anything.
 */
public class PlaceOrderUseCase {

    private final InventoryCatalog inventory;
    private final PlaceOrderTransaction transaction;
    private final IdGenerator ids;
    private final Clock clock;

    public PlaceOrderUseCase(
            InventoryCatalog inventory, PlaceOrderTransaction transaction, IdGenerator ids, Clock clock) {
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public PlaceOrderResult execute(PlaceOrderCommand command) {
        String requestHash = hash(command);
        Optional<PlaceOrderResult> replay = transaction.replay(command, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<ReservationSnapshot> found = inventory.findReservation(command.reservationId(), command.userId());
        if (found.isEmpty()) {
            return new PlaceOrderResult.ReservationNotOrderable(command.reservationId(), "Reservation not found");
        }
        ReservationSnapshot reservation = found.get();
        if (!reservation.isPending()) {
            return new PlaceOrderResult.ReservationNotOrderable(
                    command.reservationId(), "Reservation is " + reservation.status());
        }
        if (!reservation.expiresAt().isAfter(now)) {
            return new PlaceOrderResult.ReservationNotOrderable(command.reservationId(), "Reservation has expired");
        }
        Optional<Money> price = inventory.findPrice(reservation.productId());
        if (price.isEmpty()) {
            return new PlaceOrderResult.ReservationNotOrderable(
                    command.reservationId(), "Product " + reservation.productId() + " no longer exists");
        }

        Order order = Order.place(
                ids.newOrderId(),
                command.userId(),
                command.reservationId(),
                List.of(new OrderItem(reservation.productId(), reservation.quantity(), price.get())),
                now);
        return transaction.place(command, requestHash, order, now);
    }

    private static String hash(PlaceOrderCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("order:" + command.reservationId() + ":"
                                    + command.paymentSimulation().orElse(""))
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
