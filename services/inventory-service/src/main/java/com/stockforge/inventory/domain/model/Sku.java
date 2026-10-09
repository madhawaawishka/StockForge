package com.stockforge.inventory.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stock keeping unit: the business identifier of a product.
 *
 * <p>Deliberately strict (upper-case letters, digits and hyphens) so that SKUs are unambiguous in URLs, logs and
 * spreadsheets. Values are validated, never silently normalized.
 */
public record Sku(String value) {

    /** Regular expression shared with the API layer and mirrored by a database CHECK constraint. */
    public static final String FORMAT = "[A-Z0-9][A-Z0-9-]{2,63}";

    private static final Pattern PATTERN = Pattern.compile(FORMAT);

    public Sku {
        Objects.requireNonNull(value, "value must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new DomainValidationException(
                    "SKU must be 3-64 characters of A-Z, 0-9 or '-', starting with a letter or digit");
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
