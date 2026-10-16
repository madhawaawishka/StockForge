package com.stockforge.events;

/**
 * Kafka record headers set on every message. They duplicate envelope fields so that tooling (Kafka UI, dead-letter
 * inspection) can filter messages without parsing the payload; consumers read the envelope itself.
 */
public final class MessageHeaders {

    public static final String EVENT_ID = "stockforge-event-id";
    public static final String EVENT_TYPE = "stockforge-event-type";

    /** W3C trace context, so a trace continues across the asynchronous hop (spec §20, §25). */
    public static final String TRACEPARENT = "traceparent";

    private MessageHeaders() {}
}
