package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.domain.model.ProductId;
import com.stockforge.inventory.domain.model.Sku;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Catalog of RFC 9457 problem types returned by this API. Each type URI points at its documentation in
 * {@code docs/api/problems.md}, so a client developer can look up any error they receive.
 */
final class ApiProblems {

    static final String TYPE_BASE_URI = "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    private ApiProblems() {}

    /** One invalid field or parameter in a request. */
    record Violation(String field, String message) {}

    static ErrorResponseException productNotFound(ProductId id) {
        return exception(
                HttpStatus.NOT_FOUND, "product-not-found", "Product not found", "Product " + id + " does not exist");
    }

    static ErrorResponseException skuAlreadyExists(Sku sku) {
        return exception(
                HttpStatus.CONFLICT,
                "sku-already-exists",
                "SKU already exists",
                "A product with SKU " + sku + " already exists");
    }

    static ErrorResponseException invalidCursor() {
        return exception(
                HttpStatus.BAD_REQUEST,
                "invalid-cursor",
                "Invalid cursor",
                "The cursor is malformed; use the nextCursor value from a previous page");
    }

    static ProblemDetail validationFailed(String detail, List<Violation> violations) {
        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed", detail);
        if (!violations.isEmpty()) {
            problem.setProperty("errors", violations);
        }
        return problem;
    }

    static ProblemDetail malformedRequest() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "malformed-request",
                "Malformed request",
                "The request body is missing, is not valid JSON, or contains unknown or mistyped fields");
    }

    static ProblemDetail invalidParameter(String parameter) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-parameter",
                "Invalid parameter",
                "Parameter '" + parameter + "' has an invalid value");
    }

    static ProblemDetail internalError() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "An unexpected error occurred; it has been logged");
    }

    private static ErrorResponseException exception(HttpStatus status, String slug, String title, String detail) {
        return new ErrorResponseException(status, problem(status, slug, title, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE_URI + slug));
        problem.setTitle(title);
        return problem;
    }
}
