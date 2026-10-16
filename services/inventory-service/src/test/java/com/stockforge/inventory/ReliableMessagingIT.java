package com.stockforge.inventory;

import static com.stockforge.inventory.testsupport.KafkaTestClient.envelope;
import static com.stockforge.inventory.testsupport.KafkaTestClient.header;
import static com.stockforge.inventory.testsupport.KafkaTestClient.json;
import static com.stockforge.inventory.testsupport.KafkaTestClient.ofType;
import static com.stockforge.inventory.testsupport.TestData.createProductJson;
import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.stockforge.events.InventoryMessages;
import com.stockforge.events.MessageHeaders;
import com.stockforge.events.Topics;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import com.stockforge.inventory.testsupport.KafkaTestClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * The inventory side of the asynchronous workflow against a real broker (spec §16, §18, §20, §22): events leave through
 * the outbox, saga commands are applied exactly once per event id, and poison messages are dead-lettered instead of
 * blocking the partition.
 */
@InventoryIntegrationTest
class ReliableMessagingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    private KafkaTestClient kafka;

    @BeforeEach
    void setUp() {
        kafka = new KafkaTestClient(String.join(",", kafkaConnection.getBootstrapServers()));
    }

    @Test
    void aReservationIsPublishedAsReservationCreatedThroughTheOutbox() {
        String productId = createProduct(5);
        UUID userId = UUID.randomUUID();
        String reservationId = reserve(productId, userId, 2);

        ConsumerRecord<String, String> record = kafka.awaitRecord(
                Topics.INVENTORY_EVENTS, ofType(InventoryMessages.ReservationCreated.TYPE, reservationId), TIMEOUT);

        JsonNode envelope = json(record);
        assertThat(record.key()).as("partition key is the aggregate id").isEqualTo(reservationId);
        assertThat(header(record, MessageHeaders.EVENT_ID))
                .isEqualTo(envelope.get("eventId").asString());
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.at("/payload/userId").asString()).isEqualTo(userId.toString());
        assertThat(envelope.at("/payload/quantity").asInt()).isEqualTo(2);
        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> assertThat(outboxStatus(envelope.get("eventId").asString()))
                                .isEqualTo("PUBLISHED"));
    }

    @Test
    void aConfirmCommandSellsTheUnitsOnceEvenWhenDeliveredTwice() {
        String productId = createProduct(5);
        String reservationId = reserve(productId, UUID.randomUUID(), 2);
        UUID orderId = UUID.randomUUID();
        var command = envelope(
                InventoryMessages.ConfirmReservation.TYPE,
                InventoryMessages.AGGREGATE_RESERVATION,
                reservationId,
                new InventoryMessages.ConfirmReservation(UUID.fromString(reservationId), orderId));

        kafka.send(Topics.INVENTORY_COMMANDS, reservationId, command);
        kafka.send(Topics.INVENTORY_COMMANDS, reservationId, command); // at-least-once: the same event again

        kafka.awaitRecord(
                Topics.INVENTORY_EVENTS, ofType(InventoryMessages.ReservationConfirmed.TYPE, reservationId), TIMEOUT);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
            assertThat(inventory(productId, "sold_quantity")).isEqualTo(2);
            assertThat(inventory(productId, "reserved_quantity")).isZero();
        });
        assertThat(kafka.count(
                        Topics.INVENTORY_EVENTS,
                        ofType(InventoryMessages.ReservationConfirmed.TYPE, reservationId),
                        Duration.ofSeconds(3)))
                .as("the duplicate delivery produced no second confirmation")
                .isEqualTo(1);
    }

    @Test
    void aReleaseCommandCompensatesTheReservation() {
        String productId = createProduct(5);
        String reservationId = reserve(productId, UUID.randomUUID(), 1);

        kafka.send(
                Topics.INVENTORY_COMMANDS,
                reservationId,
                envelope(
                        InventoryMessages.ReleaseReservation.TYPE,
                        InventoryMessages.AGGREGATE_RESERVATION,
                        reservationId,
                        new InventoryMessages.ReleaseReservation(
                                UUID.fromString(reservationId), UUID.randomUUID(), "PAYMENT_FAILED")));

        ConsumerRecord<String, String> released = kafka.awaitRecord(
                Topics.INVENTORY_EVENTS, ofType(InventoryMessages.ReservationReleased.TYPE, reservationId), TIMEOUT);
        assertThat(json(released).at("/payload/reason").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(reservationStatus(reservationId)).isEqualTo("CANCELLED");
        assertThat(inventory(productId, "available_quantity")).isEqualTo(5);
    }

    @Test
    void aPoisonMessageGoesToTheDeadLetterTopicWithItsDiagnostics() {
        String key = "poison-" + UUID.randomUUID();

        kafka.sendRaw(Topics.INVENTORY_COMMANDS, key, "{ this is not an envelope");

        ConsumerRecord<String, String> deadLetter = kafka.awaitRecord(
                Topics.deadLetterTopicOf(Topics.INVENTORY_COMMANDS), record -> key.equals(record.key()), TIMEOUT);
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(Topics.INVENTORY_COMMANDS);
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo("com.stockforge.messaging.PoisonMessageException");
    }

    @Test
    void anExpiredReservationIsReleasedWellWithinThirtySeconds() {
        String productId = createProduct(3);
        UUID userId = UUID.randomUUID();
        String reservationId = reserve(productId, userId, 2);

        // Move the expiry into the past: the worker decides with database time, so this is all it takes.
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second', created_at = now() - interval "
                        + "'1 minute', updated_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(reservationId))
                .update();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
            assertThat(inventory(productId, "available_quantity")).isEqualTo(3);
            assertThat(jdbc.sql("SELECT active_quantity FROM user_product_limits WHERE user_id = :user")
                            .param("user", userId)
                            .query(Integer.class)
                            .single())
                    .as("the user's allowance is returned too")
                    .isZero();
        });
        kafka.awaitRecord(
                Topics.INVENTORY_EVENTS, ofType(InventoryMessages.ReservationExpired.TYPE, reservationId), TIMEOUT);
    }

    private String createProduct(int stock) {
        byte[] body = client.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createProductJson(uniqueSku(), stock))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JsonPath.read(new String(body, StandardCharsets.UTF_8), "$.id");
    }

    private String reserve(String productId, UUID userId, int quantity) {
        byte[] body = client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": %d}
                        """.formatted(productId, userId, quantity))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JsonPath.read(new String(body, StandardCharsets.UTF_8), "$.id");
    }

    private String reservationStatus(String reservationId) {
        return jdbc.sql("SELECT status FROM reservations WHERE id = :id")
                .param("id", UUID.fromString(reservationId))
                .query(String.class)
                .single();
    }

    private int inventory(String productId, String column) {
        return jdbc.sql("SELECT " + column + " FROM inventory WHERE product_id = :id")
                .param("id", UUID.fromString(productId))
                .query(Integer.class)
                .single();
    }

    private String outboxStatus(String eventId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE id = :id")
                .param("id", UUID.fromString(eventId))
                .query(String.class)
                .single();
    }
}
