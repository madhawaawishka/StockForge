package com.stockforge.inventory.domain.model;

/**
 * Thrown when a value object or aggregate is constructed with values that break a domain rule.
 *
 * <p>Messages are written for API clients: adapters may expose them verbatim, so they must never contain internal
 * details.
 */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
