package com.stockforge.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/**
 * Carries the W3C trace context across the outbox, which breaks the automatic propagation chain: the request thread
 * writes the row, a scheduler thread publishes it later (spec §20, §25).
 *
 * <p>Without a tracer (tracing disabled) every method is a no-op, so messaging never depends on tracing.
 */
final class TraceContextSupport {

    private final Tracer tracer;
    private final Propagator propagator;

    TraceContextSupport(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    static TraceContextSupport disabled() {
        return new TraceContextSupport(null, null);
    }

    /** Headers (for example {@code traceparent}) describing the span active on the calling thread. */
    Map<String, String> captureCurrent() {
        if (tracer == null || propagator == null) {
            return Map.of();
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return Map.of();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier;
    }

    /**
     * Starts a span whose parent is the context stored in {@code carrier}. The caller must end it and should open a
     * scope so that the Kafka producer observation becomes its child and propagates the trace further.
     */
    Span startChildOf(Map<String, String> carrier, String name) {
        if (tracer == null || propagator == null) {
            return null;
        }
        return propagator
                .extract(carrier, Map::get)
                .name(name)
                .kind(Span.Kind.PRODUCER)
                .start();
    }

    Tracer.SpanInScope activate(Span span) {
        return tracer.withSpan(span);
    }
}
