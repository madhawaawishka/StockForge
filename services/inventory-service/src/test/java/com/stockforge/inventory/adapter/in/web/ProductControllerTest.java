package com.stockforge.inventory.adapter.in.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockforge.inventory.application.CreateProductResult;
import com.stockforge.inventory.application.CreateProductUseCase;
import com.stockforge.inventory.application.ProductCatalogQueries;
import com.stockforge.inventory.application.ProductPage;
import com.stockforge.inventory.domain.model.Inventory;
import com.stockforge.inventory.domain.model.Money;
import com.stockforge.inventory.domain.model.Product;
import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Sku;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the product endpoints: status codes, headers, JSON shape and the Problem Details format. */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String TYPE_BASE = ApiProblems.TYPE_BASE_URI;
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.fromString("0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"));
    private static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123456Z");
    private static final Product PRODUCT = Product.create(
            PRODUCT_ID, Sku.of("SNEAKER-RED-42"), "Limited Edition Sneaker", Money.of("19.99", "USD"), NOW);

    private static final String VALID_REQUEST = """
            {"sku": "SNEAKER-RED-42", "name": "Limited Edition Sneaker",
             "price": {"amount": "19.99", "currency": "USD"}, "initialStock": 100}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateProductUseCase createProduct;

    @MockitoBean
    private ProductCatalogQueries catalog;

    @Test
    void createReturns201WithLocationAndTheCreatedProduct() throws Exception {
        given(createProduct.execute(any()))
                .willReturn(new CreateProductResult.Created(PRODUCT, Inventory.initial(PRODUCT_ID, 100, NOW)));

        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/products/" + PRODUCT_ID))
                .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.sku").value("SNEAKER-RED-42"))
                .andExpect(jsonPath("$.price.amount").value("19.99"))
                .andExpect(jsonPath("$.price.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-08T10:15:30.123456Z"));
    }

    @Test
    void duplicateSkuReturns409Problem() throws Exception {
        given(createProduct.execute(any()))
                .willReturn(new CreateProductResult.SkuAlreadyExists(Sku.of("SNEAKER-RED-42")));

        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "sku-already-exists"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/products"));
    }

    @Test
    void invalidFieldsReturn400WithOneViolationPerField() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "bad sku", "name": "", "price": {"amount": "abc", "currency": "usd"},
                                 "initialStock": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-failed"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("sku", "name", "price.amount", "price.currency", "initialStock")));
        verifyNoInteractions(createProduct);
    }

    @Test
    void unknownJsonFieldsAreRejectedInsteadOfIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "SNEAKER-RED-42", "name": "Sneaker", "price": {"amount": "1", "currency": "USD"},
                                 "initialStock": 1, "status": "INACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "malformed-request"))
                .andExpect(jsonPath("$.detail").value(not(containsString("tools.jackson"))));
        verifyNoInteractions(createProduct);
    }

    @Test
    void domainRuleViolationsThatRequestValidationCannotExpressReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "SNEAKER-RED-42", "name": "Sneaker",
                                 "price": {"amount": "19.999", "currency": "USD"}, "initialStock": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-failed"))
                .andExpect(jsonPath("$.detail").value("Amount must have at most 2 decimal places for USD"));
    }

    @Test
    void unknownProductReturns404Problem() throws Exception {
        given(catalog.findProduct(PRODUCT_ID)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/products/{id}", PRODUCT_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "product-not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/" + PRODUCT_ID));
    }

    @Test
    void malformedProductIdReturns400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-parameter"))
                .andExpect(jsonPath("$.detail").value("Parameter 'productId' has an invalid value"));
    }

    @Test
    void listReturnsAnOpaqueCursorThatRoundTrips() throws Exception {
        given(catalog.firstPage(1)).willReturn(new ProductPage(List.of(PRODUCT), Optional.of(PRODUCT_ID)));
        given(catalog.pageAfter(eq(PRODUCT_ID), eq(1))).willReturn(new ProductPage(List.of(), Optional.empty()));
        String cursor = PageCursor.encode(PRODUCT_ID);

        mockMvc.perform(get("/api/v1/products").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.nextCursor").value(cursor));

        mockMvc.perform(get("/api/v1/products").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void pageSizeOutsideTheAllowedRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        verifyNoInteractions(catalog);
    }

    @Test
    void tamperedCursorReturns400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-cursor"));
    }

    @Test
    void availabilityReportsStockFromTheSourceOfTruth() throws Exception {
        given(catalog.findInventory(PRODUCT_ID)).willReturn(Optional.of(Inventory.initial(PRODUCT_ID, 7, NOW)));

        mockMvc.perform(get("/api/v1/products/{id}/availability", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.availableQuantity").value(7))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void unexpectedErrorsReturn500WithoutLeakingInternals() throws Exception {
        given(catalog.firstPage(anyInt())).willThrow(new IllegalStateException("connection refused to db-host:5432"));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + "internal-error"))
                .andExpect(content().string(not(containsString("db-host"))));
    }
}
