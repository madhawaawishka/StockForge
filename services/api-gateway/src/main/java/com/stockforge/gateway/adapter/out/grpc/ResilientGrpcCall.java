package com.stockforge.gateway.adapter.out.grpc;

import com.stockforge.gateway.domain.model.ServiceProblem;
import com.stockforge.grpc.GrpcProblems;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs a gRPC call to one downstream service behind its bulkhead and circuit breaker (spec §23 B).
 *
 * <ul>
 *   <li><b>Bulkhead:</b> caps concurrent calls, so a slow service cannot occupy every gateway thread; excess requests
 *       are shed immediately with 503 instead of queueing (spec §23 D).
 *   <li><b>Circuit breaker:</b> after repeated infrastructure failures, calls fail fast for a cool-down period.
 *   <li>Business answers (NOT_FOUND, FAILED_PRECONDITION, ...) are successful calls from the breaker's point of view: a
 *       sold-out product is not an unhealthy service.
 * </ul>
 */
final class ResilientGrpcCall {

    /** Status codes that describe the dependency's health rather than the request. */
    private static final Set<Status.Code> INFRASTRUCTURE_FAILURES = EnumSet.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.INTERNAL,
            Status.Code.UNKNOWN,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.CANCELLED);

    private final String service;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;

    ResilientGrpcCall(String service, CircuitBreaker breaker, Bulkhead bulkhead) {
        this.service = service;
        this.breaker = breaker;
        this.bulkhead = bulkhead;
    }

    <T> T execute(Supplier<T> call) {
        Supplier<Answer<T>> guarded = () -> {
            try {
                return new Answer<>(call.get(), null);
            } catch (StatusRuntimeException error) {
                if (INFRASTRUCTURE_FAILURES.contains(error.getStatus().getCode())) {
                    throw error; // counts against the breaker
                }
                return new Answer<>(null, error);
            }
        };
        Answer<T> answer;
        try {
            answer = Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, guarded))
                    .get();
        } catch (BulkheadFullException saturated) {
            throw new ServiceProblem(
                    GrpcProblems.SERVICE_UNAVAILABLE,
                    ServiceProblem.Kind.UNAVAILABLE,
                    "The " + service + " service is saturated; retry shortly",
                    saturated);
        } catch (CallNotPermittedException open) {
            throw new ServiceProblem(
                    GrpcProblems.SERVICE_UNAVAILABLE,
                    ServiceProblem.Kind.UNAVAILABLE,
                    "The " + service + " service is temporarily unavailable; retry shortly",
                    open);
        } catch (StatusRuntimeException failure) {
            throw toProblem(failure);
        }
        if (answer.error() != null) {
            throw toProblem(answer.error());
        }
        return answer.value();
    }

    private ServiceProblem toProblem(StatusRuntimeException error) {
        String type = GrpcProblems.problemTypeOf(error);
        ServiceProblem.Kind kind = switch (error.getStatus().getCode()) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> ServiceProblem.Kind.INVALID;
            case PERMISSION_DENIED, UNAUTHENTICATED -> ServiceProblem.Kind.FORBIDDEN;
            case NOT_FOUND -> ServiceProblem.Kind.NOT_FOUND;
            case ALREADY_EXISTS, ABORTED, FAILED_PRECONDITION -> ServiceProblem.Kind.CONFLICT;
            case UNAVAILABLE, RESOURCE_EXHAUSTED -> ServiceProblem.Kind.UNAVAILABLE;
            case DEADLINE_EXCEEDED -> ServiceProblem.Kind.TIMEOUT;
            default -> ServiceProblem.Kind.INTERNAL;
        };
        String detail = kind == ServiceProblem.Kind.INTERNAL
                ? "The " + service + " service failed to process the request"
                : error.getStatus().getDescription();
        String fallbackType = switch (kind) {
            case UNAVAILABLE -> GrpcProblems.SERVICE_UNAVAILABLE;
            case TIMEOUT -> "gateway-timeout";
            case INTERNAL -> GrpcProblems.INTERNAL_ERROR;
            default -> "request-rejected";
        };
        return new ServiceProblem(type != null ? type : fallbackType, kind, detail, error);
    }

    private record Answer<T>(T value, StatusRuntimeException error) {}
}
