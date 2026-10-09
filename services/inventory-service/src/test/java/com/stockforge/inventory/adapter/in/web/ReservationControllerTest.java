package com.stockforge.inventory.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockforge.inventory.application.CancelReservationResult;
import com.stockforge.inventory.application.CancelReservationUseCase;
import com.stockforge.inventory.application.GetReservationUseCase;
import com.stockforge.inventory.application.ReserveStockResult;
import com.stockforge.inventory.application.ReserveStockUseCase;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Reservation;
import com.stockforge.inventory.domain.model.ReservationId;
import com.stockforge.inventory.domain.model.ReservationStatus;
import com.stockforge.inventory.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String TYPE_BASE = ApiProblems.TYPE_BASE_URI;

    private static final ReservationId RESERVATION_ID =
            ReservationId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final UserId USER_ID = UserId.of(UUID.fromString("0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f"));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-5a0c-7d10-8e32-0a1b2c3d4e5f"));
    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);

    private static final Reservation RESERVATION =
            Reservation.create(RESERVATION_ID, USER_ID, PRODUCT_ID, 2, EXPIRES_AT, NOW);

    private static final String VALID_RESERVE_JSON = """
            {"productId": "%s", "userId": "%s", "quantity": 2}
            """.formatted(PRODUCT_ID, USER_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReserveStockUseCase reserveStock;

    @MockitoBean
    private CancelReservationUseCase cancelReservation;

    @MockitoBean
    private GetReservationUseCase getReservation;

    @Test
    void reserveReturns201WithLocationAndBody() throws Exception {
        given(reserveStock.execute(any())).willReturn(new ReserveStockResult.Reserved(RESERVATION));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/reservations/" + RESERVATION_ID))
                .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void reserveMissingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "missing-idempotency-key"));
        verifyNoInteractions(reserveStock);
    }

    @Test
    void idempotentReplayReturns200WithReplayedHeader() throws Exception {
        given(reserveStock.execute(any())).willReturn(new ReserveStockResult.IdempotentReplay(RESERVATION));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()));
    }

    @Test
    void insufficientStockReturns409() throws Exception {
        given(reserveStock.execute(any())).willReturn(new ReserveStockResult.InsufficientStock(PRODUCT_ID, 2));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "insufficient-stock"));
    }

    @Test
    void purchaseLimitExceededReturns422() throws Exception {
        given(reserveStock.execute(any())).willReturn(new ReserveStockResult.PurchaseLimitExceeded(USER_ID, 2));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_JSON))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "purchase-limit-exceeded"));
    }

    @Test
    void getReservationReturns200() throws Exception {
        given(getReservation.execute(RESERVATION_ID)).willReturn(Optional.of(RESERVATION));

        mockMvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getUnknownReservationReturns404() throws Exception {
        given(getReservation.execute(RESERVATION_ID)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "reservation-not-found"));
    }

    @Test
    void cancelReturns200() throws Exception {
        Reservation cancelled = RESERVATION.cancel(NOW.plusSeconds(30));
        given(cancelReservation.execute(RESERVATION_ID)).willReturn(new CancelReservationResult.Cancelled(cancelled));

        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelTerminalReservationReturns409() throws Exception {
        given(cancelReservation.execute(RESERVATION_ID))
                .willReturn(new CancelReservationResult.InvalidState(RESERVATION_ID, ReservationStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", RESERVATION_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-reservation-state"));
    }
}
