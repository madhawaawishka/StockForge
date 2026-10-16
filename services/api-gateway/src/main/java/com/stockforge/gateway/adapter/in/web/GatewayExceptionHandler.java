package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.domain.model.ServiceProblem;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** One error format for every failure: RFC 9457 Problem Details (spec §12). */
@RestControllerAdvice
class GatewayExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "5";

    private final Tracer tracer;

    GatewayExceptionHandler(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer.getIfAvailable();
    }

    @ExceptionHandler(ServiceProblem.class)
    ResponseEntity<ProblemDetail> handleServiceProblem(ServiceProblem problem, HttpServletRequest request) {
        HttpStatus status = Problems.statusOf(problem.type());
        if (status == null) {
            status = switch (problem.kind()) {
                case INVALID -> HttpStatus.BAD_REQUEST;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
                case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }
        if (status.is5xxServerError()) {
            log.warn(
                    "{} {} failed: {} ({})",
                    request.getMethod(),
                    request.getRequestURI(),
                    problem.type(),
                    problem.getMessage());
        }
        HttpHeaders headers = new HttpHeaders();
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            // 503 + Retry-After: the system, not the client, is saturated (spec §23 D).
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return ResponseEntity.status(status)
                .headers(headers)
                .body(Problems.problem(
                        status,
                        problem.type(),
                        Problems.titleOf(problem.type()),
                        problem.getMessage(),
                        request.getRequestURI(),
                        tracer));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> violations = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> violations.add(
                        Map.of("field", error.getField(), "message", messageOf(error.getDefaultMessage()))));
        ProblemDetail body =
                problem(HttpStatus.BAD_REQUEST, "validation-failed", "The request contains invalid fields", request);
        body.setProperty("violations", violations);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> violations = new ArrayList<>();
        ex.getParameterValidationResults()
                .forEach(result -> result.getResolvableErrors()
                        .forEach(error -> violations.add(Map.of(
                                "field",
                                result.getMethodParameter().getParameterName() == null
                                        ? "parameter"
                                        : result.getMethodParameter().getParameterName(),
                                "message",
                                messageOf(error.getDefaultMessage())))));
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST, "validation-failed", "The request contains invalid parameters", request);
        body.setProperty("violations", violations);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser's message can reveal internal class names; it is deliberately not echoed.
        return handleExceptionInternal(
                ex,
                problem(
                        HttpStatus.BAD_REQUEST,
                        "malformed-request",
                        "The request body is not valid JSON for this operation",
                        request),
                headers,
                HttpStatus.BAD_REQUEST,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            org.springframework.web.bind.ServletRequestBindingException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing && "Idempotency-Key".equals(missing.getHeaderName())) {
            return handleExceptionInternal(
                    ex,
                    problem(
                            HttpStatus.BAD_REQUEST,
                            "missing-idempotency-key",
                            "The Idempotency-Key header is required for this operation",
                            request),
                    headers,
                    HttpStatus.BAD_REQUEST,
                    request);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** Last resort: log the details, return nothing internal (spec §28). */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", request.getDescription(false), ex);
        return handleExceptionInternal(
                ex,
                problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "An unexpected error occurred", request),
                new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null && tracer != null) {
            var span = tracer.currentSpan();
            if (span != null) {
                problem.setProperty("traceId", span.context().traceId());
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail problem(HttpStatus status, String type, String detail, WebRequest request) {
        String instance = request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : null;
        return Problems.problem(status, type, Problems.titleOf(type), detail, instance, tracer);
    }

    private static String messageOf(String message) {
        return message == null ? "is invalid" : message;
    }
}
