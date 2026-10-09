package com.stockforge.inventory;

import static com.stockforge.inventory.testsupport.TestData.createProductJson;
import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Integration tests for Reservation REST API endpoints (spec §12, §19, W2-05). */
@InventoryIntegrationTest
class ReservationApiIT {

    private static final String PROBLEM_TYPE_BASE =
            "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    @Autowired
    private RestTestClient client;

    @Test
    void reservationLifecycleWithIdempotencyAndCancellation() {
        // 1. Create a product with 10 units of initial stock
        String sku = uniqueSku();
        String productId = createProduct(sku, 10);
        UUID userId = UUID.randomUUID();
        String idempotencyKey = "idemp-" + UUID.randomUUID();

        String reserveJson = """
                {"productId": "%s", "userId": "%s", "quantity": 2}
                """.formatted(productId, userId);

        // 2. Reserve 2 units
        EntityExchangeResult<byte[]> reserveResult = client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reserveJson)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .exists("Location")
                .expectBody()
                .jsonPath("$.productId")
                .isEqualTo(productId)
                .jsonPath("$.userId")
                .isEqualTo(userId.toString())
                .jsonPath("$.quantity")
                .isEqualTo(2)
                .jsonPath("$.status")
                .isEqualTo("PENDING")
                .returnResult();

        String reservationId =
                JsonPath.read(new String(reserveResult.getResponseBody(), StandardCharsets.UTF_8), "$.id");
        URI location = reserveResult.getResponseHeaders().getLocation();
        assertThat(location.getPath()).isEqualTo("/api/v1/reservations/" + reservationId);

        // 3. Availability check: available should now be 8, reserved 2
        client.get()
                .uri("/api/v1/products/{id}/availability", productId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.availableQuantity")
                .isEqualTo(8);

        // 4. Idempotent replay: sending same key and body returns 200 OK with Idempotent-Replayed: true
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reserveJson)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Idempotent-Replayed", "true")
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(reservationId);

        // Stock must still be 8 (not decremented again)
        client.get()
                .uri("/api/v1/products/{id}/availability", productId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.availableQuantity")
                .isEqualTo(8);

        // 5. GET reservation by ID
        client.get()
                .uri("/api/v1/reservations/{id}", reservationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(reservationId)
                .jsonPath("$.status")
                .isEqualTo("PENDING");

        // 6. Cancel reservation: stock returns to available
        client.post()
                .uri("/api/v1/reservations/{id}/cancel", reservationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(reservationId)
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");

        // Availability check: available should be restored to 10
        client.get()
                .uri("/api/v1/products/{id}/availability", productId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.availableQuantity")
                .isEqualTo(10);

        // 7. Cancelling an already cancelled reservation returns 409 Conflict
        client.post()
                .uri("/api/v1/reservations/{id}/cancel", reservationId)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "invalid-reservation-state");
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        String productId = createProduct(uniqueSku(), 5);
        String body = """
                {"productId": "%s", "userId": "%s", "quantity": 1}
                """.formatted(productId, UUID.randomUUID());

        client.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "missing-idempotency-key");
    }

    @Test
    void reusingIdempotencyKeyWithDifferentPayloadReturns409() {
        String productId = createProduct(uniqueSku(), 10);
        UUID userId = UUID.randomUUID();
        String idempotencyKey = "key-" + UUID.randomUUID();

        // First call with quantity 1
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": 1}
                        """.formatted(productId, userId))
                .exchange()
                .expectStatus()
                .isCreated();

        // Second call with different quantity (2)
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": 2}
                        """.formatted(productId, userId))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "idempotency-key-conflict");
    }

    @Test
    void purchaseLimitExceededReturns422() {
        String productId = createProduct(uniqueSku(), 10);
        UUID userId = UUID.randomUUID();

        // Reserve 2 units (at limit of 2)
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "key-1-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": 2}
                        """.formatted(productId, userId))
                .exchange()
                .expectStatus()
                .isCreated();

        // Attempt to reserve a 3rd unit from same user
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "key-2-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": 1}
                        """.formatted(productId, userId))
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "purchase-limit-exceeded");
    }

    @Test
    void insufficientStockReturns409() {
        String productId = createProduct(uniqueSku(), 1);
        UUID userId = UUID.randomUUID();

        // Product has 1 unit; requesting 2 units fails
        client.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId": "%s", "userId": "%s", "quantity": 2}
                        """.formatted(productId, userId))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "insufficient-stock");
    }

    private String createProduct(String sku, int initialStock) {
        EntityExchangeResult<byte[]> result = client.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createProductJson(sku, initialStock))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult();

        return JsonPath.read(new String(result.getResponseBody(), StandardCharsets.UTF_8), "$.id");
    }
}
