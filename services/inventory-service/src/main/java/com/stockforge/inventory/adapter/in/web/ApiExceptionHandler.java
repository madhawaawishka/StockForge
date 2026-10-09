package com.stockforge.inventory.adapter.in.web;

import com.stockforge.inventory.adapter.in.web.ApiProblems.Violation;
import com.stockforge.inventory.domain.model.DomainValidationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every error into an RFC 9457 Problem Details response, so clients handle exactly one error format. Spring
 * MVC's own exceptions are handled by the base class; this class customizes the ones clients hit most.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> violations.add(new Violation(error.getField(), error.getDefaultMessage())));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> violations.add(new Violation(error.getObjectName(), error.getDefaultMessage())));
        ProblemDetail body = ApiProblems.validationFailed("The request contains invalid fields", violations);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(error -> violations.add(new Violation(parameter, error.getDefaultMessage())));
        });
        ProblemDetail body = ApiProblems.validationFailed("The request contains invalid parameters", violations);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser's message can reveal internal class names, so it is deliberately not echoed to the client.
        return handleExceptionInternal(ex, ApiProblems.malformedRequest(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String parameter = ex.getPropertyName() != null ? ex.getPropertyName() : "unknown";
        return handleExceptionInternal(ex, ApiProblems.invalidParameter(parameter), headers, status, request);
    }

    /** Domain rules that request validation could not express (for example, too many decimal places). */
    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<Object> handleDomainValidation(DomainValidationException ex, WebRequest request) {
        ProblemDetail body = ApiProblems.validationFailed(ex.getMessage(), List.of());
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /** Last resort: log the details, return nothing internal to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing {}", request.getDescription(false), ex);
        return handleExceptionInternal(
                ex, ApiProblems.internalError(), new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
