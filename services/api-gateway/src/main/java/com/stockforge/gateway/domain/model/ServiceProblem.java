package com.stockforge.gateway.domain.model;

import java.util.Objects;

/**
 * A request a downstream service could not fulfil, carrying the problem type from the service's error contract (see
 * {@code docs/api/problems.md}) so the client receives the precise RFC 9457 problem.
 */
public class ServiceProblem extends RuntimeException {

    /** Coarse class of the failure, used when the type is unknown to the gateway. */
    public enum Kind {
        INVALID,
        /** The caller is authenticated but not allowed to do this (403). */
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        UNPROCESSABLE,
        /** The dependency is down, saturated, or its circuit breaker is open: retryable (503 + Retry-After). */
        UNAVAILABLE,
        /** The dependency did not answer within the deadline (504). */
        TIMEOUT,
        INTERNAL
    }

    private final String type;
    private final Kind kind;

    public ServiceProblem(String type, Kind kind, String detail, Throwable cause) {
        super(detail, cause);
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public String type() {
        return type;
    }

    public Kind kind() {
        return kind;
    }
}
