package com.stockforge.payment.domain.model;

/** Thrown when a domain invariant or business rule is violated. */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
