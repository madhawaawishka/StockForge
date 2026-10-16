package com.stockforge.messaging;

import com.stockforge.events.EventEnvelope;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A consumed message: the envelope with its payload still untyped until the consumer picks the record type. */
public final class IncomingMessage {

    private final String topic;
    private final EventEnvelope<JsonNode> envelope;
    private final JsonMapper json;

    IncomingMessage(String topic, EventEnvelope<JsonNode> envelope, JsonMapper json) {
        this.topic = topic;
        this.envelope = envelope;
        this.json = json;
    }

    public String topic() {
        return topic;
    }

    public UUID eventId() {
        return envelope.eventId();
    }

    public String eventType() {
        return envelope.eventType();
    }

    public String correlationId() {
        return envelope.correlationId();
    }

    public EventEnvelope<JsonNode> envelope() {
        return envelope;
    }

    /**
     * Converts the payload to its record type.
     *
     * @throws PoisonMessageException if the payload does not match the type announced by the envelope
     */
    public <T> T payloadAs(Class<T> type) {
        try {
            return json.treeToValue(envelope.payload(), type);
        } catch (JacksonException | IllegalArgumentException | NullPointerException invalid) {
            throw new PoisonMessageException(
                    eventType() + " " + eventId() + " has a payload that is not a valid " + type.getSimpleName(),
                    invalid);
        }
    }

    @Override
    public String toString() {
        return eventType() + "[" + eventId() + "] on " + topic;
    }
}
