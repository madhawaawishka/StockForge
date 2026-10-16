package com.stockforge.gateway.adapter.in.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problems of the public API (spec §12). Every problem type is documented in {@code docs/api/problems.md} and
 * carries the trace id, so a user-visible error leads straight to its trace (spec §25).
 */
final class Problems {

    static final String TYPE_BASE_URI = "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    /** HTTP status of each problem type; 409 for state conflicts, 422 for business-rule violations (spec §12). */
    private static final Map<String, HttpStatus> STATUS_BY_TYPE = Map.ofEntries(
            Map.entry("validation-failed", HttpStatus.BAD_REQUEST),
            Map.entry("malformed-request", HttpStatus.BAD_REQUEST),
            Map.entry("missing-idempotency-key", HttpStatus.BAD_REQUEST),
            Map.entry("payment-simulation-disabled", HttpStatus.BAD_REQUEST),
            Map.entry("request-rejected", HttpStatus.BAD_REQUEST),
            Map.entry("unauthorized", HttpStatus.UNAUTHORIZED),
            Map.entry("forbidden", HttpStatus.FORBIDDEN),
            Map.entry("product-not-found", HttpStatus.NOT_FOUND),
            Map.entry("reservation-not-found", HttpStatus.NOT_FOUND),
            Map.entry("order-not-found", HttpStatus.NOT_FOUND),
            Map.entry("not-found", HttpStatus.NOT_FOUND),
            Map.entry("sku-already-exists", HttpStatus.CONFLICT),
            Map.entry("insufficient-stock", HttpStatus.CONFLICT),
            Map.entry("insufficient-available-stock", HttpStatus.CONFLICT),
            Map.entry("optimistic-lock-conflict", HttpStatus.CONFLICT),
            Map.entry("invalid-reservation-state", HttpStatus.CONFLICT),
            Map.entry("reservation-not-orderable", HttpStatus.CONFLICT),
            Map.entry("reservation-already-ordered", HttpStatus.CONFLICT),
            Map.entry("purchase-limit-exceeded", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("idempotency-key-reused", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("rate-limit-exceeded", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("internal-error", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("service-unavailable", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("gateway-timeout", HttpStatus.GATEWAY_TIMEOUT));

    private Problems() {}

    static HttpStatus statusOf(String type) {
        return STATUS_BY_TYPE.get(type);
    }

    static ProblemDetail problem(
            HttpStatus status, String type, String title, String detail, String instance, Tracer tracer) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE_URI + type));
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span != null) {
            problem.setProperty("traceId", span.context().traceId());
        }
        return problem;
    }

    /** "insufficient-stock" -> "Insufficient stock". */
    static String titleOf(String type) {
        String words = type.replace('-', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
