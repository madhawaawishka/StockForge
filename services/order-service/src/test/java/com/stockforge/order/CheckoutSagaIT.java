package com.stockforge.order;

import static com.stockforge.order.testsupport.KafkaTestClient.envelope;
import static com.stockforge.order.testsupport.KafkaTestClient.json;
import static com.stockforge.order.testsupport.KafkaTestClient.ofType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.stockforge.events.EventEnvelope;
import com.stockforge.events.InventoryMessages;
import com.stockforge.events.OrderMessages;
import com.stockforge.events.PaymentMessages;
import com.stockforge.events.Topics;
import com.stockforge.grpc.GrpcProblems;
import com.stockforge.order.testsupport.FakeInventory;
import com.stockforge.order.testsupport.KafkaTestClient;
import com.stockforge.order.testsupport.OrderIntegrationTest;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.GetOrderRequest;
import com.stockforge.order.v1.ListOrdersRequest;
import com.stockforge.order.v1.Order;
import com.stockforge.order.v1.OrderServiceGrpc;
import com.stockforge.order.v1.ReplayDeadLettersRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checkout saga against a real broker and database (spec §16): the order service is driven through its gRPC API and
 * by the payment and inventory events it would receive, and the commands it publishes are read back from Kafka.
 */
@OrderIntegrationTest
class CheckoutSagaIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private GrpcChannelFactory channels;

    @Autowired
    private FakeInventory.Service inventory;

    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    @Autowired
    private JdbcClient jdbc;

    private OrderServiceGrpc.OrderServiceBlockingStub orders;
    private KafkaTestClient kafka;
    private UUID user;

    @BeforeEach
    void setUp() {
        orders = OrderServiceGrpc.newBlockingStub(channels.createChannel("order-service"));
        kafka = new KafkaTestClient(String.join(",", kafkaConnection.getBootstrapServers()));
        user = UUID.randomUUID();
    }

    @Test
    void happyPathFromOrderToConfirmation() {
        String reservation = inventory.pendingReservation(user, 2, "19.99");

        Order placed = place(reservation, "SUCCEEDED");

        assertThat(placed.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(placed.getTotalAmount()).isEqualTo("39.98");
        ConsumerRecord<String, String> requestPayment = kafka.awaitRecord(
                Topics.PAYMENT_COMMANDS, ofType(PaymentMessages.RequestPayment.TYPE, placed.getId()), TIMEOUT);
        assertThat(json(requestPayment).at("/payload/simulatedOutcome").asString())
                .isEqualTo("SUCCEEDED");
        kafka.awaitRecord(Topics.ORDER_EVENTS, ofType(OrderMessages.OrderCreated.TYPE, placed.getId()), TIMEOUT);

        sendPaymentSucceeded(placed.getId());
        ConsumerRecord<String, String> confirm = kafka.awaitRecord(
                Topics.INVENTORY_COMMANDS, ofType(InventoryMessages.ConfirmReservation.TYPE, reservation), TIMEOUT);
        assertThat(confirm.key()).isEqualTo(reservation);

        kafka.send(
                Topics.INVENTORY_EVENTS,
                reservation,
                envelope(
                        InventoryMessages.ReservationConfirmed.TYPE,
                        InventoryMessages.AGGREGATE_RESERVATION,
                        reservation,
                        new InventoryMessages.ReservationConfirmed(
                                UUID.fromString(reservation), UUID.fromString(placed.getId()), UUID.randomUUID(), 2)));

        awaitStatus(placed.getId(), "CONFIRMED");
        kafka.awaitRecord(Topics.ORDER_EVENTS, ofType(OrderMessages.OrderConfirmed.TYPE, placed.getId()), TIMEOUT);
        assertThat(order(placed.getId()).getHistoryList())
                .extracting(change -> change.getToStatus())
                .containsExactly("PENDING_PAYMENT", "CONFIRMED");
    }

    @Test
    void aFailedPaymentCancelsTheOrderAndReleasesTheReservation() {
        String reservation = inventory.pendingReservation(user, 1, "10.00");
        Order placed = place(reservation, "FAILED");

        kafka.send(
                Topics.PAYMENT_EVENTS,
                placed.getId(),
                envelope(
                        PaymentMessages.PaymentFailed.TYPE,
                        PaymentMessages.AGGREGATE_PAYMENT,
                        placed.getId(),
                        new PaymentMessages.PaymentFailed(
                                UUID.randomUUID(), UUID.fromString(placed.getId()), "Card declined")));

        awaitStatus(placed.getId(), "CANCELLED");
        kafka.awaitRecord(
                Topics.INVENTORY_COMMANDS, ofType(InventoryMessages.ReleaseReservation.TYPE, reservation), TIMEOUT);
        kafka.awaitRecord(Topics.ORDER_EVENTS, ofType(OrderMessages.OrderCancelled.TYPE, placed.getId()), TIMEOUT);
    }

    @Test
    void aPaymentAfterTheReservationExpiredIsRefunded() {
        String reservation = inventory.pendingReservation(user, 1, "10.00");
        Order placed = place(reservation, "SUCCEEDED");
        sendPaymentSucceeded(placed.getId());
        kafka.awaitRecord(
                Topics.INVENTORY_COMMANDS, ofType(InventoryMessages.ConfirmReservation.TYPE, reservation), TIMEOUT);

        kafka.send(
                Topics.INVENTORY_EVENTS,
                reservation,
                envelope(
                        InventoryMessages.ReservationConfirmationRejected.TYPE,
                        InventoryMessages.AGGREGATE_RESERVATION,
                        reservation,
                        new InventoryMessages.ReservationConfirmationRejected(
                                UUID.fromString(reservation), UUID.fromString(placed.getId()), "EXPIRED")));

        awaitStatus(placed.getId(), "REFUND_PENDING");
        kafka.awaitRecord(Topics.PAYMENT_COMMANDS, ofType(PaymentMessages.RefundPayment.TYPE, placed.getId()), TIMEOUT);

        kafka.send(
                Topics.PAYMENT_EVENTS,
                placed.getId(),
                envelope(
                        PaymentMessages.PaymentRefunded.TYPE,
                        PaymentMessages.AGGREGATE_PAYMENT,
                        placed.getId(),
                        new PaymentMessages.PaymentRefunded(
                                UUID.randomUUID(), UUID.fromString(placed.getId()), "10.00", "USD")));
        awaitStatus(placed.getId(), "REFUNDED");
    }

    @Test
    void anOrderWithoutPaymentResultIsCancelledByTheSagaTimeout() {
        String reservation = inventory.pendingReservation(user, 1, "10.00");
        Order placed = place(reservation, "NO_RESPONSE");

        // The timeout is decided with database time; age the order instead of waiting two minutes.
        jdbc.sql("UPDATE orders SET created_at = now() - interval '5 minutes' WHERE id = :id")
                .param("id", UUID.fromString(placed.getId()))
                .update();

        awaitStatus(placed.getId(), "CANCELLED");
        kafka.awaitRecord(
                Topics.INVENTORY_COMMANDS, ofType(InventoryMessages.ReleaseReservation.TYPE, reservation), TIMEOUT);
    }

    @Test
    void retriesWithTheSameKeyReturnTheSameOrderAndOtherUsersCannotSeeIt() {
        String reservation = inventory.pendingReservation(user, 1, "10.00");
        CreateOrderRequest request = CreateOrderRequest.newBuilder()
                .setReservationId(reservation)
                .setUserId(user.toString())
                .setIdempotencyKey("order-" + reservation)
                .build();

        var first = orders.createOrder(request);
        var retry = orders.createOrder(request);

        assertThat(retry.getOrder().getId()).isEqualTo(first.getOrder().getId());
        assertThat(retry.getIdempotentReplay()).isTrue();
        assertThatThrownBy(() -> orders.getOrder(GetOrderRequest.newBuilder()
                        .setOrderId(first.getOrder().getId())
                        .setRequesterUserId(UUID.randomUUID().toString())
                        .build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, error -> {
                    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(GrpcProblems.problemTypeOf(error)).isEqualTo(GrpcProblems.ORDER_NOT_FOUND);
                });
        assertThat(orders.listOrders(ListOrdersRequest.newBuilder()
                                .setUserId(user.toString())
                                .build())
                        .getOrdersList())
                .extracting(Order::getId)
                .containsExactly(first.getOrder().getId());
    }

    @Test
    void anotherUsersReservationCannotBeOrdered() {
        String reservation = inventory.pendingReservation(UUID.randomUUID(), 1, "10.00");

        assertThatThrownBy(() -> place(reservation, "")).isInstanceOfSatisfying(StatusRuntimeException.class, error -> {
            assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(GrpcProblems.problemTypeOf(error)).isEqualTo(GrpcProblems.RESERVATION_NOT_ORDERABLE);
        });
    }

    @Test
    void deadLetteredEventsCanBeReplayedAndTheReplayIsAudited() throws Exception {
        String reservation = inventory.pendingReservation(user, 1, "10.00");
        Order placed = place(reservation, "FAILED");
        // A PaymentFailed that ended up in the dead-letter topic, for example after a database outage.
        EventEnvelope<PaymentMessages.PaymentFailed> failed = envelope(
                PaymentMessages.PaymentFailed.TYPE,
                PaymentMessages.AGGREGATE_PAYMENT,
                placed.getId(),
                new PaymentMessages.PaymentFailed(UUID.randomUUID(), UUID.fromString(placed.getId()), "Declined"));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            ProducerRecord<String, String> deadLetter = new ProducerRecord<>(
                    Topics.deadLetterTopicOf(Topics.PAYMENT_EVENTS),
                    placed.getId(),
                    JsonMapper.builder().build().writeValueAsString(failed));
            deadLetter
                    .headers()
                    .add("kafka_dlt-original-topic", Topics.PAYMENT_EVENTS.getBytes(StandardCharsets.UTF_8));
            producer.send(deadLetter).get(10, TimeUnit.SECONDS);
        }
        String admin = UUID.randomUUID().toString();

        int replayed = orders.replayDeadLetters(ReplayDeadLettersRequest.newBuilder()
                        .setTopic(Topics.PAYMENT_EVENTS)
                        .setMaxMessages(1000)
                        .setActorId(admin)
                        .build())
                .getReplayed();

        assertThat(replayed).isPositive();
        awaitStatus(placed.getId(), "CANCELLED");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit_logs WHERE actor_id = :actor AND action = 'DEAD_LETTERS_REPLAYED'")
                        .param("actor", admin)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    private Order place(String reservation, String simulation) {
        return orders.createOrder(CreateOrderRequest.newBuilder()
                        .setReservationId(reservation)
                        .setUserId(user.toString())
                        .setIdempotencyKey(UUID.randomUUID().toString())
                        .setPaymentSimulation(simulation)
                        .build())
                .getOrder();
    }

    private void sendPaymentSucceeded(String orderId) {
        kafka.send(
                Topics.PAYMENT_EVENTS,
                orderId,
                envelope(
                        PaymentMessages.PaymentSucceeded.TYPE,
                        PaymentMessages.AGGREGATE_PAYMENT,
                        orderId,
                        new PaymentMessages.PaymentSucceeded(
                                UUID.randomUUID(), UUID.fromString(orderId), "10.00", "USD")));
    }

    private Order order(String orderId) {
        return orders.getOrder(GetOrderRequest.newBuilder()
                        .setOrderId(orderId)
                        .setRequesterUserId(user.toString())
                        .build())
                .getOrder();
    }

    private void awaitStatus(String orderId, String status) {
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(order(orderId).getStatus()).isEqualTo(status));
    }
}
