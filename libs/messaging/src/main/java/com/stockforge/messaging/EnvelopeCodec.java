package com.stockforge.messaging;

import com.stockforge.events.EventEnvelope;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts envelopes to and from the JSON value of a Kafka record. */
public final class EnvelopeCodec {

    private static final TypeReference<EventEnvelope<JsonNode>> UNTYPED_ENVELOPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final JsonMapper json;

    public EnvelopeCodec(JsonMapper json) {
        this.json = json;
    }

    String encode(EventEnvelope<?> envelope) {
        return json.writeValueAsString(envelope);
    }

    String encodeHeaders(Map<String, String> headers) {
        return json.writeValueAsString(headers);
    }

    Map<String, String> decodeHeaders(String headersJson) {
        return json.readValue(headersJson, HEADERS);
    }

    /**
     * Reads a record value into an envelope whose payload is still untyped.
     *
     * @throws PoisonMessageException if the value is not a valid envelope; retrying cannot fix that
     */
    public IncomingMessage decode(String topic, String value) {
        if (value == null || value.isBlank()) {
            throw new PoisonMessageException("Empty message on " + topic);
        }
        try {
            return new IncomingMessage(topic, json.readValue(value, UNTYPED_ENVELOPE), json);
        } catch (JacksonException | IllegalArgumentException | NullPointerException invalid) {
            throw new PoisonMessageException("Message on " + topic + " is not a valid event envelope", invalid);
        }
    }
}
