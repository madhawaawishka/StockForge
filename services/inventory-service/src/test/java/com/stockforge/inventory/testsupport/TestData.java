package com.stockforge.inventory.testsupport;

import java.util.Locale;
import java.util.UUID;

/** Builders for test data that never collides with data created by other tests sharing the same database. */
public final class TestData {

    private TestData() {}

    public static String uniqueSku() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(Locale.ROOT);
    }

    public static String createProductJson(String sku, int initialStock) {
        return """
                {"sku": "%s", "name": "Integration Test Product",
                 "price": {"amount": "19.99", "currency": "USD"}, "initialStock": %d}
                """.formatted(sku, initialStock);
    }
}
