package com.stockforge.inventory.adapter.in.grpc;

import com.stockforge.grpc.GrpcProblems;
import com.stockforge.inventory.adapter.in.DependencyFailures;
import com.stockforge.inventory.domain.model.DomainValidationException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * Maps exceptions that escape a gRPC method to statuses (spec §12): invalid input to {@code INVALID_ARGUMENT}, a
 * temporarily unavailable database to {@code UNAVAILABLE} (retryable; the gateway answers 503 + Retry-After), anything
 * else to {@code INTERNAL} without leaking its message.
 */
@Component
class InventoryGrpcExceptionHandler implements GrpcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcExceptionHandler.class);

    @Override
    public StatusException handleException(Throwable exception) {
        if (exception instanceof StatusRuntimeException alreadyMapped) {
            return new StatusException(alreadyMapped.getStatus(), alreadyMapped.getTrailers());
        }
        if (exception instanceof DomainValidationException invalid) {
            return status(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, invalid.getMessage());
        }
        if (DependencyFailures.isUnavailable(exception)) {
            log.warn("Database temporarily unavailable: {}", exception.toString());
            return status(
                    Status.UNAVAILABLE, GrpcProblems.SERVICE_UNAVAILABLE, "The service is temporarily unavailable");
        }
        log.error("Unhandled exception in gRPC call", exception);
        return status(Status.INTERNAL, GrpcProblems.INTERNAL_ERROR, "Internal error");
    }

    private static StatusException status(Status status, String problemType, String detail) {
        Metadata trailers = new Metadata();
        trailers.put(GrpcProblems.PROBLEM_TYPE, problemType);
        return status.withDescription(detail).asException(trailers);
    }
}
