package com.stockforge.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stockforge.gateway.domain.model.OrderView;
import com.stockforge.gateway.domain.model.UserIdentity;
import com.stockforge.gateway.domain.port.OrderClient;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderGatewayUseCaseBolaTest {

    private OrderClient orderClient;
    private OrderGatewayUseCase useCase;

    private final UUID aliceId = UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10");
    private final UUID bobId = UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f");
    private final String orderId = "order-123";

    @BeforeEach
    void setUp() {
        orderClient = mock(OrderClient.class);
        useCase = new OrderGatewayUseCase(orderClient);
    }

    @Test
    void allowsOwnerToViewOwnOrder() {
        OrderView order = new OrderView(
                orderId,
                aliceId.toString(),
                "res-1",
                "CONFIRMED",
                "99.99",
                "USD",
                Collections.emptyList(),
                "2026-10-11T12:00:00Z");
        when(orderClient.getOrder(orderId)).thenReturn(Optional.of(order));

        UserIdentity alice = UserIdentity.of(aliceId);
        Optional<OrderView> result = useCase.getOrder(orderId, alice);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(orderId);
    }

    @Test
    void returnsEmptyWhenAnotherUserAttemptsToViewOrder_BolaDefense() {
        // Spec §28: BOLA defense — return 404/empty rather than 403 to prevent resource enumeration
        OrderView order = new OrderView(
                orderId,
                aliceId.toString(),
                "res-1",
                "CONFIRMED",
                "99.99",
                "USD",
                Collections.emptyList(),
                "2026-10-11T12:00:00Z");
        when(orderClient.getOrder(orderId)).thenReturn(Optional.of(order));

        UserIdentity bob = UserIdentity.of(bobId);
        Optional<OrderView> result = useCase.getOrder(orderId, bob);

        assertThat(result).isEmpty();
    }

    @Test
    void allowsAdminToViewAnyOrder() {
        OrderView order = new OrderView(
                orderId,
                aliceId.toString(),
                "res-1",
                "CONFIRMED",
                "99.99",
                "USD",
                Collections.emptyList(),
                "2026-10-11T12:00:00Z");
        when(orderClient.getOrder(orderId)).thenReturn(Optional.of(order));

        UserIdentity admin = UserIdentity.of(bobId, "admin", Set.of("ROLE_ADMIN"));
        Optional<OrderView> result = useCase.getOrder(orderId, admin);

        assertThat(result).isPresent();
    }
}
