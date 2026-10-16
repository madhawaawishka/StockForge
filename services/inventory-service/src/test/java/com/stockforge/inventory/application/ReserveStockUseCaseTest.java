package com.stockforge.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.Topics;
import com.stockforge.inventory.domain.model.DomainValidationException;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import com.stockforge.inventory.domain.port.StockReservationResult;
import com.stockforge.inventory.domain.port.StockReservationStrategy;
import com.stockforge.inventory.domain.port.StrategyType;
import com.stockforge.inventory.testsupport.InventoryFixture;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReserveStockUseCaseTest {

    private static final UserId ALICE = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final UserId BOB = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e70"));
    private static final UserId CAROL = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e71"));

    private InventoryFixture fixture;
    private ReserveStockUseCase useCase;
    private ProductId product;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture();
        useCase = useCaseWith(fixture.strategy);
        product = fixture.product("SNEAKER-1", 3);
    }

    @Test
    void reservesStockAndPublishesReservationCreatedInTheSameTransaction() {
        ReserveStockResult result = useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 2));

        assertThat(result).isInstanceOf(ReserveStockResult.Reserved.class);
        var reserved = (ReserveStockResult.Reserved) result;
        assertThat(reserved.replayed()).isFalse();
        assertThat(reserved.reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reserved.reservation().expiresAt()).isEqualTo(InventoryFixture.START.plus(Duration.ofMinutes(10)));
        assertThat(fixture.inventoryOf(product).availableQuantity()).isEqualTo(1);
        assertThat(fixture.inventoryOf(product).reservedQuantity()).isEqualTo(2);
        assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isEqualTo(2);

        assertThat(fixture.outbox.messages()).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo(Topics.INVENTORY_EVENTS);
            assertThat(message.aggregateId())
                    .isEqualTo(reserved.reservation().id().toString());
            assertThat(message.payload())
                    .isEqualTo(new InventoryMessages.ReservationCreated(
                            reserved.reservation().id().value(),
                            ALICE.value(),
                            product.value(),
                            2,
                            reserved.reservation().expiresAt()));
        });
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheReservationWithoutReservingAgain() {
        var first = (ReserveStockResult.Reserved) useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 1));

        ReserveStockResult retry = useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 1));

        assertThat(retry).isEqualTo(new ReserveStockResult.Reserved(first.reservation(), true));
        assertThat(fixture.inventoryOf(product).reservedQuantity()).isEqualTo(1);
        assertThat(fixture.reservations.count()).isEqualTo(1);
        assertThat(fixture.outbox.messages()).hasSize(1);
    }

    @Test
    void aRetryOfASoldOutRequestReplaysSoldOutEvenAfterStockReturns() {
        useCase.execute(new ReserveStockCommand("bob-1", BOB, product, 2));
        useCase.execute(new ReserveStockCommand("carol-1", CAROL, product, 1));
        ReserveStockResult soldOut = useCase.execute(new ReserveStockCommand("alice-1", ALICE, product, 1));
        assertThat(soldOut).isInstanceOf(ReserveStockResult.InsufficientStock.class);

        fixture.inventories.releaseReservedStock(product, 1, InventoryFixture.START);

        // Same key, same request: the client sees the same answer it got the first time (spec §19).
        assertThat(useCase.execute(new ReserveStockCommand("alice-1", ALICE, product, 1)))
                .isInstanceOf(ReserveStockResult.InsufficientStock.class);
        // A new key is a new request.
        assertThat(useCase.execute(new ReserveStockCommand("alice-2", ALICE, product, 1)))
                .isInstanceOf(ReserveStockResult.Reserved.class);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 1));

        assertThat(useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 2)))
                .isEqualTo(new ReserveStockResult.IdempotencyKeyReused("key-1"));
    }

    @Test
    void keysAreScopedPerUser() {
        useCase.execute(new ReserveStockCommand("shared-key", ALICE, product, 1));

        assertThat(useCase.execute(new ReserveStockCommand("shared-key", BOB, product, 1)))
                .isInstanceOfSatisfying(
                        ReserveStockResult.Reserved.class,
                        r -> assertThat(r.replayed()).isFalse());
    }

    @Test
    void enforcesThePurchaseLimitAcrossReservations() {
        useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 1));
        useCase.execute(new ReserveStockCommand("key-2", ALICE, product, 1));

        assertThat(useCase.execute(new ReserveStockCommand("key-3", ALICE, product, 1)))
                .isEqualTo(new ReserveStockResult.PurchaseLimitExceeded(ALICE, 2));
        assertThat(fixture.inventoryOf(product).reservedQuantity()).isEqualTo(2);
    }

    @Test
    void insufficientStockLeavesNoTraceInStockOrLimits() {
        assertThat(useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 2)))
                .isInstanceOf(ReserveStockResult.Reserved.class);
        assertThat(useCase.execute(new ReserveStockCommand("key-2", BOB, product, 2)))
                .isEqualTo(new ReserveStockResult.InsufficientStock(product, 2));

        assertThat(fixture.purchaseLimits.heldBy(BOB, product)).isZero();
        assertThat(fixture.inventoryOf(product).availableQuantity()).isEqualTo(1);
        assertThat(fixture.outbox.messages()).hasSize(1);
    }

    @Test
    void anOptimisticConflictForgetsTheKeySoTheClientCanRetry() {
        StockReservationStrategy alwaysConflicting = new StockReservationStrategy() {
            @Override
            public StrategyType type() {
                return StrategyType.OPTIMISTIC_LOCKING;
            }

            @Override
            public StockReservationResult reserve(com.stockforge.inventory.domain.model.Reservation reservation) {
                return new StockReservationResult.OptimisticConflict();
            }
        };

        assertThat(useCaseWith(alwaysConflicting).execute(new ReserveStockCommand("key-1", ALICE, product, 1)))
                .isEqualTo(new ReserveStockResult.OptimisticConflict(product));
        assertThat(fixture.purchaseLimits.heldBy(ALICE, product)).isZero();

        // The retry is a fresh attempt, not a replay of the conflict.
        assertThat(useCase.execute(new ReserveStockCommand("key-1", ALICE, product, 1)))
                .isInstanceOfSatisfying(
                        ReserveStockResult.Reserved.class,
                        r -> assertThat(r.replayed()).isFalse());
    }

    @Test
    void unknownProductIsReportedAndNotStoredUnderTheKey() {
        ProductId unknown = ProductId.of(UUID.randomUUID());

        assertThat(useCase.execute(new ReserveStockCommand("key-1", ALICE, unknown, 1)))
                .isEqualTo(new ReserveStockResult.ProductNotFound(unknown));
        assertThat(fixture.idempotencyKeys.findByKeyAndUser("key-1", ALICE)).isEmpty();
    }

    @Test
    void rejectsMalformedIdempotencyKeysAndQuantities() {
        assertThatThrownBy(() -> new ReserveStockCommand("has space", ALICE, product, 1))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new ReserveStockCommand("x".repeat(129), ALICE, product, 1))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new ReserveStockCommand("key", ALICE, product, 0))
                .isInstanceOf(DomainValidationException.class);
    }

    private ReserveStockUseCase useCaseWith(StockReservationStrategy strategy) {
        return new ReserveStockUseCase(
                fixture.products,
                strategy,
                fixture.reservations,
                fixture.idempotencyKeys,
                fixture.purchaseLimits,
                fixture.outbox,
                fixture.ids,
                fixture.clock,
                InventoryFixture.POLICY);
    }
}
