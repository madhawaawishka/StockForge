package com.stockforge.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stockforge.gateway.domain.model.ReservationView;
import com.stockforge.gateway.domain.model.UserIdentity;
import com.stockforge.gateway.domain.port.InventoryClient;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReservationGatewayUseCaseBolaTest {

    private InventoryClient inventoryClient;
    private ReservationGatewayUseCase useCase;

    private final UUID aliceId = UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10");
    private final UUID bobId = UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f");
    private final String resId = "res-123";

    @BeforeEach
    void setUp() {
        inventoryClient = mock(InventoryClient.class);
        useCase = new ReservationGatewayUseCase(inventoryClient);
    }

    @Test
    void allowsOwnerToViewOwnReservation() {
        ReservationView res =
                new ReservationView(resId, "prod-1", aliceId.toString(), 1, "PENDING", "2026-10-11T12:00:00Z");
        when(inventoryClient.getReservation(resId)).thenReturn(Optional.of(res));

        UserIdentity alice = UserIdentity.of(aliceId);
        Optional<ReservationView> result = useCase.getReservation(resId, alice);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(resId);
    }

    @Test
    void returnsEmptyWhenAnotherUserAttemptsToViewReservation_BolaDefense() {
        // Spec §28: BOLA defense — return 404/empty rather than 403 to prevent resource enumeration
        ReservationView res =
                new ReservationView(resId, "prod-1", aliceId.toString(), 1, "PENDING", "2026-10-11T12:00:00Z");
        when(inventoryClient.getReservation(resId)).thenReturn(Optional.of(res));

        UserIdentity bob = UserIdentity.of(bobId);
        Optional<ReservationView> result = useCase.getReservation(resId, bob);

        assertThat(result).isEmpty();
    }

    @Test
    void allowsAdminToViewAnyReservation() {
        ReservationView res =
                new ReservationView(resId, "prod-1", aliceId.toString(), 1, "PENDING", "2026-10-11T12:00:00Z");
        when(inventoryClient.getReservation(resId)).thenReturn(Optional.of(res));

        UserIdentity admin = UserIdentity.of(bobId, "admin", Set.of("ROLE_ADMIN"));
        Optional<ReservationView> result = useCase.getReservation(resId, admin);

        assertThat(result).isPresent();
    }
}
