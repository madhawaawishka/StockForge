package com.stockforge.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.order.domain.model.Money;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
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

/** Object-level authorization on reads (spec §28, OWASP API1): only the owner — or an operator — sees an order. */
class OrderQueriesTest {

    private static final UserId ALICE = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final UserId MALLORY = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e99"));

    private OrderFixture fixture;
    private OrderQueries queries;
    private OrderId aliceOrder;

    @BeforeEach
    void placeAlicesOrder() {
        fixture = new OrderFixture();
        queries = new OrderQueries(fixture.orders);
        ReservationId reservation = ReservationId.of(UUID.randomUUID());
        fixture.inventory.reservation(
                new ReservationSnapshot(
                        reservation,
                        ALICE,
                        ProductId.of(UUID.randomUUID()),
                        1,
                        "PENDING",
                        OrderFixture.START.plus(Duration.ofMinutes(10))),
                Money.of("19.99", "USD"));
        PlaceOrderUseCase placeOrder = new PlaceOrderUseCase(
                fixture.inventory,
                new PlaceOrderTransaction(fixture.orders, fixture.idempotencyKeys, fixture.outbox, OrderFixture.POLICY),
                fixture.ids,
                fixture.clock);
        PlaceOrderResult result =
                placeOrder.execute(new PlaceOrderCommand("key-1", ALICE, reservation, Optional.empty()));
        aliceOrder = ((PlaceOrderResult.Placed) result).order().id();
    }

    @Test
    void theOwnerSeesTheOrder() {
        assertThat(queries.find(aliceOrder, Optional.of(ALICE)))
                .map(Order::userId)
                .contains(ALICE);
    }

    @Test
    void anotherUsersOrderLooksLikeItDoesNotExist() {
        assertThat(queries.find(aliceOrder, Optional.of(MALLORY))).isEmpty();
        assertThat(queries.list(MALLORY, Optional.empty(), 20).orders()).isEmpty();
    }

    @Test
    void anOperatorWithoutRequesterScopeSeesAnyOrder() {
        assertThat(queries.find(aliceOrder, Optional.empty())).isPresent();
    }
}
