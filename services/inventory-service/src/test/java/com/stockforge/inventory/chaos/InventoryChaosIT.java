package com.stockforge.inventory.chaos;

import static com.stockforge.inventory.testsupport.TestData.createProductJson;
import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.stockforge.events.InventoryMessages;
import com.stockforge.inventory.InventoryServiceApplication;
import com.stockforge.inventory.testsupport.InfrastructureContainers;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Real failure injection against the running service (spec §27, W5-06). PostgreSQL and Redis sit behind Toxiproxy, so
 * the service sees genuine network faults (refused connections, silent partitions) rather than mocked exceptions; Kafka
 * is paused at the container level, which freezes the broker the way a long GC pause or a network split would. A second
 * service instance is started and stopped mid-request to verify graceful shutdown (W6-02), as in a rolling deploy.
 *
 * <p>Each experiment states the hypothesis it verifies and restores the fault in {@link #heal()}, so a failed assertion
 * cannot leak a broken dependency into the next experiment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class InventoryChaosIT {

    private static final int POSTGRES_PROXY_PORT = 8666;
    private static final int REDIS_PROXY_PORT = 8667;

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(InfrastructureContainers.POSTGRES_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    private static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(NETWORK);

    private static final KafkaContainer KAFKA = new KafkaContainer(InfrastructureContainers.KAFKA_IMAGE);

    private static final Proxy POSTGRES_PROXY;
    private static final Proxy REDIS_PROXY;

    static {
        // Started once for this class; Testcontainers' Ryuk reaper removes them when the JVM exits.
        Startables.deepStart(POSTGRES, REDIS, TOXIPROXY, KAFKA).join();
        try {
            ToxiproxyClient toxiproxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            POSTGRES_PROXY = toxiproxy.createProxy("postgres", "0.0.0.0:" + POSTGRES_PROXY_PORT, "postgres:5432");
            REDIS_PROXY = toxiproxy.createProxy("redis", "0.0.0.0:" + REDIS_PROXY_PORT, "redis:6379");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Connection settings shared by the test's own context and the extra instance of the graceful-shutdown test. */
    private static Map<String, Object> serviceProperties() {
        String host = TOXIPROXY.getHost();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "spring.datasource.url",
                "jdbc:postgresql://%s:%d/%s"
                        .formatted(host, TOXIPROXY.getMappedPort(POSTGRES_PROXY_PORT), POSTGRES.getDatabaseName()));
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        // Tighter client-side bounds than production, so a partition is detected within the test's patience.
        properties.put("spring.datasource.hikari.data-source-properties.socketTimeout", "3");
        properties.put("spring.datasource.hikari.validation-timeout", "1000");
        properties.put("spring.data.redis.host", host);
        properties.put("spring.data.redis.port", TOXIPROXY.getMappedPort(REDIS_PROXY_PORT));
        properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        return properties;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        serviceProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void heal() throws IOException {
        for (Proxy proxy : new Proxy[] {POSTGRES_PROXY, REDIS_PROXY}) {
            proxy.enable();
            for (var toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        }
        var docker = DockerClientFactory.instance().client();
        if (Boolean.TRUE.equals(docker.inspectContainerCmd(KAFKA.getContainerId())
                .exec()
                .getState()
                .getPaused())) {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    /** Hypothesis: Redis is an optimization (NFR-9), so losing it costs latency, never availability or correctness. */
    @Test
    void redisOutageFallsBackToTheDatabase() throws IOException {
        String productId = createProduct(10);

        REDIS_PROXY.disable();

        client.get()
                .uri("/api/v1/products/{id}", productId)
                .exchange()
                .expectStatus()
                .isOk();
        String availability = getBody("/api/v1/products/" + productId + "/availability");
        assertThat(JsonPath.<Integer>read(availability, "$.availableQuantity")).isEqualTo(10);
        assertThat(JsonPath.<Boolean>read(availability, "$.approximate"))
                .as("without a cache, availability comes straight from PostgreSQL")
                .isFalse();
        assertThat(reserve(productId, 2)).isEqualTo(HttpStatus.CREATED);
        assertThat(inventory(productId)).isEqualTo(8);
    }

    /**
     * Hypothesis: when PostgreSQL silently stops answering, requests fail fast with 503 + Retry-After instead of
     * hanging or returning 500, and the service recovers on its own once the network heals.
     */
    @Test
    void databasePartitionFailsFastWith503AndRecovers() throws IOException {
        String productId = createProduct(10);

        // A "timeout" toxic with timeout 0 swallows all traffic without closing the connection: a black-hole partition.
        POSTGRES_PROXY.toxics().timeout("partition", ToxicDirection.UPSTREAM, 0);

        long started = System.nanoTime();
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reservationJson(productId, 1))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader()
                .exists("Retry-After")
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .value(String.class, type -> assertThat(type).endsWith("#service-unavailable"));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("bounded by the socket timeout, not by the client's patience")
                .isLessThan(Duration.ofSeconds(10));

        POSTGRES_PROXY.toxics().get("partition").remove();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(reserve(productId, 1)).isEqualTo(HttpStatus.CREATED));
        assertThat(inventory(productId)).isEqualTo(9);
    }

    /**
     * Hypothesis: a Kafka outage does not affect reservations (the outbox is written in the business transaction), the
     * backlog is visible in the outbox metrics, and every buffered event is published once the broker returns.
     */
    @Test
    void kafkaOutageIsBufferedByTheOutboxAndDrainedAfterRecovery() {
        String productId = createProduct(10);
        var docker = DockerClientFactory.instance().client();

        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();

        String reservationId = reserveAndGetId(productId, 2);
        UUID eventId = reservationCreatedEventId(reservationId);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .until(() -> "PENDING".equals(outboxStatus(eventId)));
        assertThat(meters.get("stockforge.outbox.pending.events").gauge().value())
                .as("the backlog is visible to the outbox alert")
                .isPositive();

        docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();

        await().atMost(Duration.ofSeconds(60)).until(() -> "PUBLISHED".equals(outboxStatus(eventId)));
    }

    /**
     * Hypothesis: stopping an instance (SIGTERM in a rolling deploy) lets in-flight requests finish and commit, refuses
     * new connections, and loses no event: what the stopped instance wrote to the outbox is published by the survivor.
     */
    @Test
    void aStoppingInstanceDrainsInFlightRequestsAndLosesNoEvents() throws Exception {
        String productId = createProduct(10);
        ConfigurableApplicationContext instance = startSecondInstance();
        int port = instance.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        MeterRegistry instanceMeters = instance.getBean(MeterRegistry.class);
        HttpClient http = HttpClient.newHttpClient();

        // Every database round trip now takes 300 ms, so the reservation is still running when shutdown begins.
        POSTGRES_PROXY.toxics().latency("slow-database", ToxicDirection.DOWNSTREAM, 300);
        CompletableFuture<HttpResponse<String>> inFlight =
                http.sendAsync(reservationRequest(port, productId), HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(10)).until(() -> activeRequests(instanceMeters) > 0);

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(instance::close);

        HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode())
                .as("the in-flight reservation completes during graceful shutdown: %s", response.body())
                .isEqualTo(HttpStatus.CREATED.value());
        shutdown.get(30, TimeUnit.SECONDS);
        assertThat(instance.isActive()).isFalse();
        assertThatThrownBy(() -> HttpClient.newHttpClient()
                        .send(reservationRequest(port, productId), HttpResponse.BodyHandlers.ofString()))
                .as("a stopped instance accepts no new requests")
                .isInstanceOf(IOException.class);

        POSTGRES_PROXY.toxics().get("slow-database").remove();
        String reservationId = JsonPath.read(response.body(), "$.id");
        assertThat(inventory(productId)).isEqualTo(9);
        UUID eventId = reservationCreatedEventId(reservationId);
        await().atMost(Duration.ofSeconds(30)).until(() -> "PUBLISHED".equals(outboxStatus(eventId)));
    }

    /** A second, independent instance against the same infrastructure, as in a horizontally scaled deployment. */
    private static ConfigurableApplicationContext startSecondInstance() throws IOException {
        Map<String, Object> properties = serviceProperties();
        properties.put("server.port", 0);
        properties.put("spring.grpc.server.port", freePort());
        // Command-line arguments outrank application.yml, exactly like the test context's dynamic properties.
        String[] args = properties.entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(InventoryServiceApplication.class).run(args);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static long activeRequests(MeterRegistry registry) {
        var active = registry.find("http.server.requests.active").longTaskTimer();
        return active == null ? 0 : active.activeTasks();
    }

    private static HttpRequest reservationRequest(int port, String productId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/reservations"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(reservationJson(productId, 1)))
                .build();
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

    private String getBody(String uri) {
        byte[] body = client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return new String(body, StandardCharsets.UTF_8);
    }

    private HttpStatus reserve(String productId, int quantity) {
        return HttpStatus.valueOf(client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reservationJson(productId, quantity))
                .exchange()
                .returnResult()
                .getStatus()
                .value());
    }

    private String reserveAndGetId(String productId, int quantity) {
        byte[] body = client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reservationJson(productId, quantity))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JsonPath.read(new String(body, StandardCharsets.UTF_8), "$.id");
    }

    private static String reservationJson(String productId, int quantity) {
        return """
                {"productId": "%s", "userId": "%s", "quantity": %d}
                """.formatted(productId, UUID.randomUUID(), quantity);
    }

    private int inventory(String productId) {
        return jdbc.sql("SELECT available_quantity FROM inventory WHERE product_id = :id")
                .param("id", UUID.fromString(productId))
                .query(Integer.class)
                .single();
    }

    private UUID reservationCreatedEventId(String reservationId) {
        return jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = :id AND event_type = :type")
                .param("id", reservationId)
                .param("type", InventoryMessages.ReservationCreated.TYPE)
                .query(UUID.class)
                .single();
    }

    private String outboxStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }
}
