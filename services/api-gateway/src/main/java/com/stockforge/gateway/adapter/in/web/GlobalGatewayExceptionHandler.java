package com.stockforge.gateway.adapter.in.web;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalGatewayExceptionHandler {

    private static final String PROBLEMS_BASE =
            "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#";

    @ExceptionHandler({CallNotPermittedException.class, BulkheadFullException.class})
    public ResponseEntity<ProblemDetail> handleResilienceExceptions(Exception ex, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Downstream service is temporarily unavailable or overloaded");
        pd.setTitle("Service Unavailable");
        pd.setType(URI.create(PROBLEMS_BASE + "service-unavailable"));
        pd.setInstance(URI.create(request.getRequestURI()));
        enrichTraceId(pd);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "5");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(pd);
    }

    @ExceptionHandler(StatusRuntimeException.class)
    public ProblemDetail handleGrpcStatus(StatusRuntimeException ex, HttpServletRequest request) {
        Status.Code code = ex.getStatus().getCode();
        HttpStatus status;
        String problemType;
        String title;

        switch (code) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                problemType = "not-found";
                title = "Resource Not Found";
            }
            case ALREADY_EXISTS -> {
                status = HttpStatus.CONFLICT;
                problemType = "idempotency-key-conflict";
                title = "Conflict";
            }
            case FAILED_PRECONDITION -> {
                status = HttpStatus.UNPROCESSABLE_CONTENT;
                problemType = "insufficient-stock";
                title = "Precondition Failed";
            }
            case INVALID_ARGUMENT -> {
                status = HttpStatus.BAD_REQUEST;
                problemType = "malformed-request";
                title = "Bad Request";
            }
            case DEADLINE_EXCEEDED -> {
                status = HttpStatus.GATEWAY_TIMEOUT;
                problemType = "internal-error";
                title = "Gateway Timeout";
            }
            case UNAVAILABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                problemType = "internal-error";
                title = "Service Unavailable";
            }
            default -> {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                problemType = "internal-error";
                title = "Internal Server Error";
            }
        }

        ProblemDetail pd =
                ProblemDetail.forStatusAndDetail(status, ex.getStatus().getDescription());
        pd.setTitle(title);
        pd.setType(URI.create(PROBLEMS_BASE + problemType));
        pd.setInstance(URI.create(request.getRequestURI()));
        enrichTraceId(pd);
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred.");
        pd.setTitle("Internal Server Error");
        pd.setType(URI.create(PROBLEMS_BASE + "internal-error"));
        pd.setInstance(URI.create(request.getRequestURI()));
        enrichTraceId(pd);
        return pd;
    }

    private void enrichTraceId(ProblemDetail pd) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            pd.setProperty("traceId", traceId);
        }
    }
}
