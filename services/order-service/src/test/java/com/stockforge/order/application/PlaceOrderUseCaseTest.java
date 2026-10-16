package com.stockforge.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.events.OrderMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.OrderStatus;
import com.stockforge.order.domain.model.ProductId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.InventoryCatalog.ReservationSnapshot;
import com.stockforge.order.testsupport.OrderFixture;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceOrderUseCaseTest {

    private static final UserId ALICE = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final UserId MALLORY = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e99"));
    private static final ProductId PRODUCT = ProductId.of(UUID.randomUUID());

    private OrderFixture fixture;
    private PlaceOrderUseCase useCase;
    private ReservationId reservation;

    @BeforeEach
    void setUp() {
        fixture = new OrderFixture();
        useCase = new PlaceOrderUseCase(
                fixture.inventory,
                new PlaceOrderTransaction(fixture.orders, fixture.idempotencyKeys, fixture.outbox, OrderFixture.POLICY),
                fixture.ids,
                fixture.clock);
        reservation = ReservationId.of(UUID.randomUUID());
        fixture.inventory.reservation(
                new ReservationSnapshot(
                        reservation, ALICE, PRODUCT, 2, "PENDING", OrderFixture.START.plus(Duration.ofMinutes(10))),
                Money.of("19.99", "USD"));
    }

    @Test
    void placesTheOrderAtTheCurrentPriceAndRequestsPaymentInTheSameTransaction() {
        PlaceOrderResult result = useCase.execute(command("key-1", ALICE, Optional.of("FAILED")));

        assertThat(result).isInstanceOfSatisfying(PlaceOrderResult.Placed.class, placed -> {
            assertThat(placed.replayed()).isFalse();
            assertThat(placed.order().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(placed.order().totalAmount()).isEqualTo(Money.of("39.98", "USD"));
            assertThat(placed.order().items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(PRODUCT);
                assertThat(item.quantity()).isEqualTo(2);
            });
        });
        assertThat(fixture.outbox.messages())
                .extracting(m -> m.topic() + ":" + m.eventType())
                .containsExactly(
                        Topics.PAYMENT_COMMANDS + ":" + PaymentMessages.RequestPayment.TYPE,
                        Topics.ORDER_EVENTS + ":" + OrderMessages.OrderCreated.TYPE);
        assertThat(fixture.outbox.payloads(PaymentMessages.RequestPayment.class))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.amount()).isEqualTo("39.98");
                    assertThat(request.simulatedOutcome()).isEqualTo("FAILED");
                });
    }

    @Test
    void aRetryReturnsTheSameOrderWithoutCallingInventoryAgain() {
        var first = (PlaceOrderResult.Placed) useCase.execute(command("key-1", ALICE, Optional.empty()));
        int callsAfterFirst = fixture.inventory.calls();

        PlaceOrderResult retry = useCase.execute(command("key-1", ALICE, Optional.empty()));

        assertThat(retry).isEqualTo(new PlaceOrderResult.Placed(first.order(), true));
        assertThat(fixture.inventory.calls()).isEqualTo(callsAfterFirst);
        assertThat(fixture.outbox.eventTypes()).hasSize(2);
    }

    @Test
    void oneOrderPerReservationEvenWithDifferentKeys() {
        useCase.execute(command("key-1", ALICE, Optional.empty()));

        assertThat(useCase.execute(command("key-2", ALICE, Optional.empty())))
                .isEqualTo(new PlaceOrderResult.ReservationAlreadyOrdered(reservation));
    }

    @Test
    void anotherUsersReservationCannotBeOrdered() {
        assertThat(useCase.execute(command("key-1", MALLORY, Optional.empty())))
                .isInstanceOf(PlaceOrderResult.ReservationNotOrderable.class);
        assertThat(fixture.outbox.messages()).isEmpty();
    }

    @Test
    void anExpiredReservationCannotBeOrdered() {
        fixture.clock.advance(Duration.ofMinutes(11));

        assertThat(useCase.execute(command("key-1", ALICE, Optional.empty())))
                .isEqualTo(new PlaceOrderResult.ReservationNotOrderable(reservation, "Reservation has expired"));
    }

    @Test
    void reusingAKeyForAnotherRequestIsRejected() {
        useCase.execute(command("key-1", ALICE, Optional.empty()));

        assertThat(useCase.execute(command("key-1", ALICE, Optional.of("FAILED"))))
                .isEqualTo(new PlaceOrderResult.IdempotencyKeyReused("key-1"));
    }

    private PlaceOrderCommand command(String key, UserId user, Optional<String> simulation) {
        return new PlaceOrderCommand(key, user, reservation, simulation);
    }
}
