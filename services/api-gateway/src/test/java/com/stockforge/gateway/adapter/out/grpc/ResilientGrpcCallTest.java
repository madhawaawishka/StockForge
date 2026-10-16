package com.stockforge.gateway.adapter.out.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockforge.gateway.domain.model.ServiceProblem;
import com.stockforge.grpc.GrpcProblems;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

/** Business answers must never open the circuit breaker; infrastructure failures must (spec §23 B). */
class ResilientGrpcCallTest {

    private final CircuitBreaker breaker = CircuitBreaker.of(
            "test",
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .build());
    private final ResilientGrpcCall calls = new ResilientGrpcCall("inventory", breaker, Bulkhead.ofDefaults("test"));

    @Test
    void aSoldOutAnswerKeepsItsProblemTypeAndDoesNotTripTheBreaker() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> calls.execute(() -> {
                        throw GrpcProblems.problem(
                                Status.FAILED_PRECONDITION, GrpcProblems.INSUFFICIENT_STOCK, "sold out");
                    }))
                    .isInstanceOfSatisfying(ServiceProblem.class, problem -> {
                        assertThat(problem.type()).isEqualTo(GrpcProblems.INSUFFICIENT_STOCK);
                        assertThat(problem.kind()).isEqualTo(ServiceProblem.Kind.CONFLICT);
                    });
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedUnavailabilityOpensTheBreakerAndThenFailsFast() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> calls.execute(() -> {
                        throw Status.UNAVAILABLE.asRuntimeException();
                    }))
                    .isInstanceOfSatisfying(
                            ServiceProblem.class,
                            problem -> assertThat(problem.kind()).isEqualTo(ServiceProblem.Kind.UNAVAILABLE));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> calls.execute(() -> "never called"))
                .isInstanceOfSatisfying(
                        ServiceProblem.class,
                        problem -> assertThat(problem.type()).isEqualTo(GrpcProblems.SERVICE_UNAVAILABLE));
    }

    @Test
    void aMissedDeadlineIsAGatewayTimeoutAndInternalErrorsDoNotLeakDetails() {
        assertThatThrownBy(() -> calls.execute(() -> {
                    throw Status.DEADLINE_EXCEEDED.asRuntimeException();
                }))
                .isInstanceOfSatisfying(
                        ServiceProblem.class,
                        problem -> assertThat(problem.kind()).isEqualTo(ServiceProblem.Kind.TIMEOUT));
        assertThatThrownBy(() -> calls.execute(() -> {
                    throw Status.INTERNAL
                            .withDescription("NullPointerException at Foo.java:42")
                            .asRuntimeException();
                }))
                .isInstanceOfSatisfying(
                        ServiceProblem.class,
                        problem -> assertThat(problem.getMessage()).doesNotContain("NullPointerException"));
    }
}
