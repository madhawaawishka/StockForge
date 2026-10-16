package com.stockforge.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * The error contract of the internal gRPC APIs (spec §12): a gRPC status code plus a machine-readable problem type in
 * the {@value #TRAILER_NAME} trailer. The type values are the anchors of {@code docs/api/problems.md}, so the gateway
 * turns a gRPC error into the matching RFC 9457 problem without parsing descriptions.
 */
public final class GrpcProblems {

    public static final String TRAILER_NAME = "stockforge-problem-type";
    public static final Metadata.Key<String> PROBLEM_TYPE =
            Metadata.Key.of(TRAILER_NAME, Metadata.ASCII_STRING_MARSHALLER);

    // Problem types; keep in sync with docs/api/problems.md.
    public static final String VALIDATION_FAILED = "validation-failed";
    public static final String PRODUCT_NOT_FOUND = "product-not-found";
    public static final String SKU_ALREADY_EXISTS = "sku-already-exists";
    public static final String INSUFFICIENT_STOCK = "insufficient-stock";
    public static final String INSUFFICIENT_AVAILABLE_STOCK = "insufficient-available-stock";
    public static final String PURCHASE_LIMIT_EXCEEDED = "purchase-limit-exceeded";
    public static final String OPTIMISTIC_LOCK_CONFLICT = "optimistic-lock-conflict";
    public static final String IDEMPOTENCY_KEY_REUSED = "idempotency-key-reused";
    public static final String RESERVATION_NOT_FOUND = "reservation-not-found";
    public static final String INVALID_RESERVATION_STATE = "invalid-reservation-state";
    public static final String RESERVATION_NOT_ORDERABLE = "reservation-not-orderable";
    public static final String RESERVATION_ALREADY_ORDERED = "reservation-already-ordered";
    public static final String ORDER_NOT_FOUND = "order-not-found";
    public static final String SERVICE_UNAVAILABLE = "service-unavailable";
    public static final String INTERNAL_ERROR = "internal-error";

    private GrpcProblems() {}

    public static StatusRuntimeException problem(Status status, String problemType, String detail) {
        Metadata trailers = new Metadata();
        trailers.put(PROBLEM_TYPE, problemType);
        return status.withDescription(detail).asRuntimeException(trailers);
    }

    /** The problem type carried by an error, or {@code null} if the server did not set one. */
    public static String problemTypeOf(StatusRuntimeException error) {
        Metadata trailers = error.getTrailers();
        return trailers == null ? null : trailers.get(PROBLEM_TYPE);
    }
}
