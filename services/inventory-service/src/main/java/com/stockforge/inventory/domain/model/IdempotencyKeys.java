package com.stockforge.inventory.domain.model;

import java.util.regex.Pattern;

/**
 * Format rule for client-supplied idempotency keys (spec §19). Keys are opaque to the server; the rule only keeps them
 * bounded and printable so they are safe to store, log and compare.
 */
public final class IdempotencyKeys {

    public static final int MAX_LENGTH = 128;

    /** Printable ASCII from '!' (0x21) to '~' (0x7E): no spaces, no control characters. */
    private static final Pattern FORMAT = Pattern.compile("[!-~]{1," + MAX_LENGTH + "}");

    private IdempotencyKeys() {}

    public static String requireValid(String key) {
        if (key == null || !FORMAT.matcher(key).matches()) {
            throw new DomainValidationException(
                    "Idempotency-Key must be 1-" + MAX_LENGTH + " printable ASCII characters without spaces");
        }
        return key;
    }
}
