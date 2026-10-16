package com.stockforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockforge.gateway.testsupport.FakeDownstreams;
import com.stockforge.gateway.testsupport.Tokens;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The public API end to end through the real security chain, rate limiter (Redis container) and gRPC clients, with
 * in-process fakes for the Inventory and Order services (spec §12, §23, §28).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTestGrpcTransport
@Import({FakeDownstreams.class, GatewayApiIT.Redis.class})
class GatewayApiIT {

    private static final String PROBLEMS =
            "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    @TestConfiguration(proxyBeanMethods = false)
    static class Redis {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:8.8.3").withExposedPorts(6379);
        }
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private FakeDownstreams.Inventory inventory;

    @Autowired
    private FakeDownstreams.Orders orders;

    private final String user = UUID.randomUUID().toString();

    private String reserve(String token, String productId) {
        return client.post()
                .uri("/api/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"productId\": \"" + productId + "\", \"quantity\": 1}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @Nested
    class Authentication {

        @Test
        void catalogReadsArePublicAndSupportConditionalRequests() {
            String etag = client.get()
                    .uri("/api/v1/products/{id}", FakeDownstreams.PRODUCT)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .exists(HttpHeaders.ETAG)
                    .returnResult()
                    .getResponseHeaders()
                    .getETag();

            client.get()
                    .uri("/api/v1/products/{id}", FakeDownstreams.PRODUCT)
                    .header(HttpHeaders.IF_NONE_MATCH, etag)
                    .exchange()
                    .expectStatus()
                    .isNotModified();
        }

        /** The second read comes from the inventory cache (approximate) with the same numbers: still a 304. */
        @Test
        void availabilityPollsAreRevalidatedAndAnswered304WhileNothingChanged() {
            String etag = client.get()
                    .uri("/api/v1/products/{id}/availability", FakeDownstreams.PRODUCT)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .cacheControl(CacheControl.noCache())
                    .expectBody()
                    .jsonPath("$.availableQuantity")
                    .isEqualTo(10)
                    .returnResult()
                    .getResponseHeaders()
                    .getETag();

            client.get()
                    .uri("/api/v1/products/{id}/availability", FakeDownstreams.PRODUCT)
                    .header(HttpHeaders.IF_NONE_MATCH, etag)
                    .exchange()
                    .expectStatus()
                    .isNotModified();
        }

        @Test
        void writesWithoutATokenAreUnauthorizedProblems() {
            client.post()
                    .uri("/api/v1/reservations")
                    .header("Idempotency-Key", "k")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "unauthorized");
        }

        @Test
        void forgedExpiredOrMisaddressedTokensAreRejected() {
            List<String> invalid = List.of(
                    Tokens.token(
                            "some-other-secret-that-is-at-least-32-bytes",
                            user,
                            "stockforge-auth",
                            "stockforge-api",
                            List.of(),
                            Duration.ofMinutes(5)),
                    Tokens.token(
                            Tokens.SECRET,
                            user,
                            "stockforge-auth",
                            "stockforge-api",
                            List.of(),
                            Duration.ofMinutes(-5)),
                    Tokens.token(
                            Tokens.SECRET, user, "evil-issuer", "stockforge-api", List.of(), Duration.ofMinutes(5)),
                    Tokens.token(
                            Tokens.SECRET, user, "stockforge-auth", "another-api", List.of(), Duration.ofMinutes(5)),
                    Tokens.token(
                            Tokens.SECRET,
                            "not-a-user-id",
                            "stockforge-auth",
                            "stockforge-api",
                            List.of(),
                            Duration.ofMinutes(5)));
            for (String token : invalid) {
                client.get()
                        .uri("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchange()
                        .expectStatus()
                        .isUnauthorized();
            }
        }

        @Test
        void administrationNeedsTheAdminRole() {
            String body = """
                    {"sku": "NEW-1", "name": "New", "price": {"amount": "1.00", "currency": "USD"}, "initialStock": 1}
                    """;
            client.post()
                    .uri("/api/v1/admin/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "forbidden");
            client.post()
                    .uri("/api/v1/admin/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.admin(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus()
                    .isCreated();
        }
    }

    @Nested
    class ObjectLevelAuthorization {

        @Test
        void theOwnerIsAlwaysTheTokenSubjectNeverTheBody() {
            client.post()
                    .uri("/api/v1/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"productId\": \"" + FakeDownstreams.PRODUCT + "\", \"quantity\": 1, \"userId\": \""
                            + UUID.randomUUID() + "\"}")
                    .exchange()
                    .expectStatus()
                    .isBadRequest(); // unknown field: mass assignment is rejected outright

            reserve(Tokens.user(user), FakeDownstreams.PRODUCT);
            assertThat(inventory.reserveRequests.getLast().getUserId()).isEqualTo(user);
        }

        @Test
        void anotherUsersReservationIsNotFound() {
            String reservation =
                    com.jayway.jsonpath.JsonPath.read(reserve(Tokens.user(user), FakeDownstreams.PRODUCT), "$.id");

            client.get()
                    .uri("/api/v1/reservations/{id}", reservation)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + Tokens.user(UUID.randomUUID().toString()))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "reservation-not-found");
            client.get()
                    .uri("/api/v1/reservations/{id}", reservation)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Nested
    class ErrorContract {

        @Test
        void soldOutIsConflictAndOverTheLimitIsUnprocessable() {
            client.post()
                    .uri("/api/v1/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"productId\": \"" + FakeDownstreams.SOLD_OUT + "\", \"quantity\": 1}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "insufficient-stock");
            client.post()
                    .uri("/api/v1/reservations")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + Tokens.user(UUID.randomUUID().toString()))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"productId\": \"" + FakeDownstreams.OVER_LIMIT + "\", \"quantity\": 1}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "purchase-limit-exceeded");
        }

        @Test
        void aMissingIdempotencyKeyIsRejectedAtTheEdge() {
            client.post()
                    .uri("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"reservationId\": \"" + UUID.randomUUID() + "\"}")
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "missing-idempotency-key");
        }

        @Test
        void anUnavailableServiceIs503WithRetryAfter() {
            client.post()
                    .uri("/api/v1/reservations")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + Tokens.user(UUID.randomUUID().toString()))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"productId\": \"" + FakeDownstreams.BROKEN + "\", \"quantity\": 1}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .exists(HttpHeaders.RETRY_AFTER);
        }

        @Test
        void ordersAreAcceptedAsynchronouslyAndSimulationIsOffByDefault() {
            client.post()
                    .uri("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"reservationId\": \"" + UUID.randomUUID() + "\"}")
                    .exchange()
                    .expectStatus()
                    .isAccepted()
                    .expectHeader()
                    .exists(HttpHeaders.LOCATION);
            assertThat(orders.createRequests.getLast().getUserId()).isEqualTo(user);

            client.post()
                    .uri("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.user(user))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Payment-Simulation", "FAILED")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"reservationId\": \"" + UUID.randomUUID() + "\"}")
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "payment-simulation-disabled");
        }
    }

    @Nested
    class RateLimiting {

        @Test
        void aUserBurstingPastTheLimitGets429WithRetryAfter() {
            String burstingUser = Tokens.user(UUID.randomUUID().toString());
            for (int i = 0; i < 5; i++) {
                reserve(burstingUser, FakeDownstreams.PRODUCT);
            }

            client.post()
                    .uri("/api/v1/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + burstingUser)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"productId\": \"" + FakeDownstreams.PRODUCT + "\", \"quantity\": 1}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(429)
                    .expectHeader()
                    .exists(HttpHeaders.RETRY_AFTER)
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(PROBLEMS + "rate-limit-exceeded");

            // Other users have their own budget.
            reserve(Tokens.user(UUID.randomUUID().toString()), FakeDownstreams.PRODUCT);
        }
    }
}
