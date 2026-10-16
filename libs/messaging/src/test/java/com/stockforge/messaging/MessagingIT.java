package com.stockforge.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.events.InventoryMessages;
import com.stockforge.events.MessageHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The messaging guarantees, proven against a real PostgreSQL and a real Kafka broker. */
@Testcontainers
class MessagingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MessagingProperties.Outbox OUTBOX_CONFIG = new MessagingProperties.Outbox(
            true, Duration.ofMillis(100), 100, Duration.ofSeconds(5), Duration.ofDays(7), Duration.ofHours(1));

    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private static KafkaTemplate<String, String> kafka;
    private static OutboxWriter writer;
    private static EnvelopeCodec codec;

    private String topic;

    @BeforeAll
    static void setUpInfrastructure() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("stockforge/messaging/schema-postgresql.sql"))
                .execute(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        kafka = template(KAFKA.getBootstrapServers());
        codec = new EnvelopeCodec(JSON);
        writer = new OutboxWriter(jdbc, codec, TraceContextSupport.disabled(), Clock.systemUTC());
    }

    @AfterAll
    static void tearDown() {
        kafka.destroy();
    }

    @BeforeEach
    void cleanTablesAndCreateTopic() throws Exception {
        jdbc.sql("TRUNCATE outbox_events, processed_events").update();
        topic = "test-" + UUID.randomUUID();
        // Services declare their topics at startup (the broker does not auto-create them), so the tests do too.
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void outboxWritesRefuseToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.append(message("r-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    void relayPublishesEnvelopesInWriteOrderAndMarksThemPublished() {
        List<UUID> written = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < 5; i++) {
                written.add(writer.append(message("reservation-1")));
            }
        });

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        assertThat(relay(kafka, meters).publishPendingBatch()).isEqualTo(5);

        List<ConsumerRecord<String, String>> received = consume(5);
        assertThat(received).extracting(ConsumerRecord::key).containsOnly("reservation-1");
        assertThat(received)
                .extracting(record -> header(record, MessageHeaders.EVENT_ID))
                .containsExactlyElementsOf(written.stream().map(UUID::toString).toList());

        JsonNode envelope = JSON.readTree(received.getFirst().value());
        assertThat(envelope.get("eventType").asString()).isEqualTo(InventoryMessages.ReservationExpired.TYPE);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("quantity").asInt()).isEqualTo(2);

        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
        assertThat(meters.get("stockforge.outbox.publish.lag").timer().count())
                .as("the commit-to-acknowledgement lag is recorded per event (NFR-7)")
                .isEqualTo(5);
    }

    @Test
    void eventsStayPendingWhileTheBrokerIsUnreachableAndArePublishedAfterRecovery() {
        transaction.executeWithoutResult(status -> writer.append(message("reservation-2")));

        KafkaTemplate<String, String> unreachable = template("localhost:1");
        try {
            assertThat(relay(unreachable).publishPendingBatch()).isZero();
        } finally {
            unreachable.destroy();
        }
        assertThat(jdbc.sql("SELECT status, attempts, last_error FROM outbox_events")
                        .query((rs, rowNum) -> rs.getString("status") + "/" + rs.getInt("attempts") + "/"
                                + (rs.getString("last_error") != null))
                        .single())
                .isEqualTo("PENDING/1/true");

        assertThat(relay(kafka).publishPendingBatch()).isEqualTo(1);
        assertThat(consume(1)).hasSize(1);
    }

    @Test
    void onlyOneRelayInstancePublishesAtATime() throws Exception {
        transaction.executeWithoutResult(status -> writer.append(message("reservation-3")));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService otherInstance = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = otherInstance.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('stockforge.outbox-relay.' || current_schema()))")
                        .query(rs -> {});
                lockHeld.countDown();
                await(release);
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(relay(kafka).publishPendingBatch()).isZero();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(relay(kafka).publishPendingBatch()).isEqualTo(1);
        } finally {
            otherInstance.shutdownNow();
        }
    }

    @Test
    void inboxAcceptsAnEventOncePerConsumerGroup() {
        ProcessedMessageStore inbox = new ProcessedMessageStore(jdbc);
        UUID eventId = UUID.randomUUID();

        assertThat(markProcessed(inbox, "group-a", eventId)).isTrue();
        assertThat(markProcessed(inbox, "group-a", eventId)).isFalse();
        assertThat(markProcessed(inbox, "group-b", eventId)).isTrue();
    }

    @Test
    void aRolledBackAttemptDoesNotMarkTheEventAsProcessed() {
        ProcessedMessageStore inbox = new ProcessedMessageStore(jdbc);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    inbox.markProcessed("group-a", eventId);
                    throw new IllegalStateException("business change failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(markProcessed(inbox, "group-a", eventId)).isTrue();
    }

    @Test
    void retentionDeletesOnlyOldPublishedRowsAndOldInboxEntries() {
        transaction.executeWithoutResult(status -> {
            writer.append(message("old"));
            writer.append(message("recent"));
            writer.append(message("pending"));
        });
        jdbc.sql("""
                UPDATE outbox_events SET status = 'PUBLISHED',
                    published_at = CASE aggregate_id WHEN 'old' THEN now() - interval '8 days' ELSE now() END
                WHERE aggregate_id IN ('old', 'recent')
                """).update();
        jdbc.sql("INSERT INTO processed_events VALUES ('g', gen_random_uuid(), now() - interval '15 days'),"
                        + " ('g', gen_random_uuid(), now())")
                .update();

        MessagingProperties properties = new MessagingProperties(
                OUTBOX_CONFIG,
                new MessagingProperties.Inbox(Duration.ofDays(14)),
                new MessagingProperties.Topics(List.of(), List.of(), 1, (short) 1),
                new MessagingProperties.Consumer(4, Duration.ofMillis(500), Duration.ofSeconds(5)));

        assertThat(new MessagingRetention(jdbc, properties, true).purge()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT aggregate_id FROM outbox_events ORDER BY seq")
                        .query(String.class)
                        .list())
                .containsExactly("recent", "pending");
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    private OutgoingMessage message(String aggregateId) {
        UUID reservation = UUID.randomUUID();
        return new OutgoingMessage(
                topic,
                InventoryMessages.AGGREGATE_RESERVATION,
                aggregateId,
                InventoryMessages.ReservationExpired.TYPE,
                new InventoryMessages.ReservationExpired(reservation, UUID.randomUUID(), UUID.randomUUID(), 2),
                aggregateId,
                aggregateId);
    }

    private static OutboxRelay relay(KafkaTemplate<String, String> template) {
        return relay(template, new SimpleMeterRegistry());
    }

    private static OutboxRelay relay(KafkaTemplate<String, String> template, MeterRegistry meters) {
        return new OutboxRelay(
                jdbc,
                transaction,
                template,
                codec,
                TraceContextSupport.disabled(),
                OUTBOX_CONFIG,
                new OutboxMetrics(meters));
    }

    private static KafkaTemplate<String, String> template(String bootstrapServers) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                1_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                3_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                2_000)));
    }

    private List<ConsumerRecord<String, String>> consume(int expected) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "verifier-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(20);
            while (received.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(received::add);
            }
            return received;
        }
    }

    private static boolean markProcessed(ProcessedMessageStore inbox, String group, UUID eventId) {
        return Boolean.TRUE.equals(transaction.execute(status -> inbox.markProcessed(group, eventId)));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
