package com.stockforge.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the JSON form of every message to a hand-written golden file in {@code src/test/resources/contracts}.
 *
 * <p>Renaming, removing or retyping a field changes the JSON and fails this test, which is the point: within a schema
 * version only additive changes are allowed (spec §18, ADR-012). A deliberate breaking change needs a new schema
 * version, not an edited golden file.
 */
class MessageContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID RESERVATION = UUID.fromString("0192f5c4-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0192f5c4-0000-7000-8000-000000000002");
    private static final UUID PRODUCT = UUID.fromString("0192f5c4-0000-7000-8000-000000000003");
    private static final UUID ORDER = UUID.fromString("0192f5c4-0000-7000-8000-000000000004");
    private static final UUID PAYMENT = UUID.fromString("0192f5c4-0000-7000-8000-000000000005");

    static Stream<Arguments> messages() {
        return Stream.of(
                contract(
                        InventoryMessages.ReservationCreated.TYPE,
                        new InventoryMessages.ReservationCreated(
                                RESERVATION, USER, PRODUCT, 2, Instant.parse("2026-10-08T10:25:30.123456Z"))),
                contract(
                        InventoryMessages.ReservationConfirmed.TYPE,
                        new InventoryMessages.ReservationConfirmed(RESERVATION, ORDER, PRODUCT, 2)),
                contract(
                        InventoryMessages.ReservationConfirmationRejected.TYPE,
                        new InventoryMessages.ReservationConfirmationRejected(RESERVATION, ORDER, "EXPIRED")),
                contract(
                        InventoryMessages.ReservationReleased.TYPE,
                        new InventoryMessages.ReservationReleased(
                                RESERVATION, USER, PRODUCT, 2, "PAYMENT_FAILED", ORDER)),
                contract(
                        InventoryMessages.ReservationExpired.TYPE,
                        new InventoryMessages.ReservationExpired(RESERVATION, USER, PRODUCT, 2)),
                contract(
                        InventoryMessages.ConfirmReservation.TYPE,
                        new InventoryMessages.ConfirmReservation(RESERVATION, ORDER)),
                contract(
                        InventoryMessages.ReleaseReservation.TYPE,
                        new InventoryMessages.ReleaseReservation(RESERVATION, ORDER, "PAYMENT_FAILED")),
                contract(
                        OrderMessages.OrderCreated.TYPE,
                        new OrderMessages.OrderCreated(ORDER, USER, RESERVATION, "39.98", "USD")),
                contract(
                        OrderMessages.OrderConfirmed.TYPE,
                        new OrderMessages.OrderConfirmed(ORDER, USER, RESERVATION, "39.98", "USD")),
                contract(
                        OrderMessages.OrderCancelled.TYPE,
                        new OrderMessages.OrderCancelled(ORDER, USER, RESERVATION, "Payment failed")),
                contract(
                        OrderMessages.OrderRefundPending.TYPE,
                        new OrderMessages.OrderRefundPending(
                                ORDER, USER, RESERVATION, "Reservation EXPIRED before confirmation")),
                contract(
                        OrderMessages.OrderRefunded.TYPE, new OrderMessages.OrderRefunded(ORDER, USER, "39.98", "USD")),
                contract(
                        PaymentMessages.RequestPayment.TYPE,
                        new PaymentMessages.RequestPayment(ORDER, USER, "39.98", "USD", "FAILED")),
                contract(
                        PaymentMessages.RefundPayment.TYPE,
                        new PaymentMessages.RefundPayment(ORDER, "Reservation EXPIRED before confirmation")),
                contract(
                        PaymentMessages.PaymentSucceeded.TYPE,
                        new PaymentMessages.PaymentSucceeded(PAYMENT, ORDER, "39.98", "USD")),
                contract(
                        PaymentMessages.PaymentFailed.TYPE,
                        new PaymentMessages.PaymentFailed(PAYMENT, ORDER, "Card declined")),
                contract(PaymentMessages.PaymentTimedOut.TYPE, new PaymentMessages.PaymentTimedOut(PAYMENT, ORDER)),
                contract(
                        PaymentMessages.PaymentRefunded.TYPE,
                        new PaymentMessages.PaymentRefunded(PAYMENT, ORDER, "39.98", "USD")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void serializesExactlyAsTheGoldenFile(String type, Object message) {
        JsonNode actual = JSON.valueToTree(message);
        assertThat(actual).isEqualTo(golden(type));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void deserializesTheGoldenFile(String type, Object message) {
        assertThat(JSON.treeToValue(golden(type), message.getClass())).isEqualTo(message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void typeNameMatchesTheRecordName(String type, Object message) {
        assertThat(type).isEqualTo(message.getClass().getSimpleName());
    }

    @Test
    void envelopeKeepsItsShape() {
        var envelope = new EventEnvelope<>(
                UUID.fromString("0192f5c4-0000-7000-8000-0000000000aa"),
                InventoryMessages.ReservationExpired.TYPE,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                InventoryMessages.AGGREGATE_RESERVATION,
                RESERVATION.toString(),
                Instant.parse("2026-10-08T10:25:31Z"),
                RESERVATION.toString(),
                RESERVATION.toString(),
                new InventoryMessages.ReservationExpired(RESERVATION, USER, PRODUCT, 2));

        JsonNode actual = JSON.valueToTree(envelope);
        assertThat(actual).isEqualTo(golden("EventEnvelope"));
    }

    @Test
    void deadLetterTopicNaming() {
        assertThat(Topics.deadLetterTopicOf(Topics.PAYMENT_EVENTS)).isEqualTo("payment-events.DLT");
    }

    private static Arguments contract(String type, Object message) {
        return Arguments.of(type, message);
    }

    private static JsonNode golden(String type) {
        try (InputStream in = MessageContractTest.class.getResourceAsStream("/contracts/" + type + ".json")) {
            assertThat(in).as("golden file for %s", type).isNotNull();
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
