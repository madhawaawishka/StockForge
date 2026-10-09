package com.stockforge.inventory;

import static com.stockforge.inventory.testsupport.TestData.createProductJson;
import static com.stockforge.inventory.testsupport.TestData.uniqueSku;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.stockforge.inventory.testsupport.InventoryIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The product API end to end: real HTTP server, real PostgreSQL, Flyway schema. */
@InventoryIntegrationTest
class ProductApiIT {

    private static final String PROBLEM_TYPE_BASE =
            "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    /** PostgreSQL's UUID ordering (unsigned bytes), which is the order keyset pagination must return. */
    private static final Comparator<UUID> POSTGRES_UUID_ORDER = Comparator.comparing(
                    UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    @Autowired
    private RestTestClient client;

    @Test
    void createdProductIsReadableAndHasItsInitialStockAvailable() {
        String sku = uniqueSku();

        EntityExchangeResult<byte[]> created = createProduct(sku, 100);
        String createdBody = new String(created.getResponseBody(), StandardCharsets.UTF_8);
        String id = JsonPath.read(createdBody, "$.id");
        URI location = created.getResponseHeaders().getLocation();

        assertThat(location).isNotNull();
        assertThat(location.getPath()).isEqualTo("/api/v1/products/" + id);
        // Identical representation after a database round trip, including microsecond timestamps.
        client.get()
                .uri(location)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json(createdBody, JsonCompareMode.STRICT);
        client.get()
                .uri("/api/v1/products/{id}/availability", id)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.availableQuantity")
                .isEqualTo(100)
                .jsonPath("$.inStock")
                .isEqualTo(true);
    }

    @Test
    void duplicateSkuIsRejectedWithConflict() {
        String sku = uniqueSku();
        createProduct(sku, 1);

        client.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createProductJson(sku, 5))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "sku-already-exists");
    }

    @Test
    void pagingVisitsEveryProductExactlyOnceInKeyOrder() {
        List<String> createdIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String body = new String(createProduct(uniqueSku(), i).getResponseBody(), StandardCharsets.UTF_8);
            createdIds.add(JsonPath.read(body, "$.id"));
        }

        List<UUID> visited = new ArrayList<>();
        String cursor = null;
        do {
            String uri = "/api/v1/products?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            String page = new String(
                    client.get()
                            .uri(uri)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .returnResult()
                            .getResponseBody(),
                    StandardCharsets.UTF_8);
            List<String> ids = JsonPath.read(page, "$.items[*].id");
            ids.forEach(id -> visited.add(UUID.fromString(id)));
            cursor = JsonPath.read(page, "$.nextCursor");
        } while (cursor != null);

        assertThat(visited).doesNotHaveDuplicates().isSortedAccordingTo(POSTGRES_UUID_ORDER);
        assertThat(visited)
                .containsAll(createdIds.stream().map(UUID::fromString).toList());
    }

    @Test
    void invalidRequestsAreRejectedWithProblemDetails() {
        client.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"sku": "lowercase", "name": "x", "price": {"amount": "1.00", "currency": "USD"},
                         "initialStock": 1}
                        """)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "validation-failed")
                .jsonPath("$.errors[0].field")
                .isEqualTo("sku");
    }

    @Test
    void unknownProductReturnsNotFound() {
        client.get()
                .uri("/api/v1/products/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(PROBLEM_TYPE_BASE + "product-not-found");
    }

    private EntityExchangeResult<byte[]> createProduct(String sku, int initialStock) {
        return client.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createProductJson(sku, initialStock))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo(sku)
                .returnResult();
    }
}
