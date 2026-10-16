package com.stockforge.messaging;

/**
 * A message that can never be processed successfully, however often it is retried: malformed JSON, an unknown event
 * type, a payload that breaks a contract. The consumer error handler sends it straight to the dead-letter topic instead
 * of retrying (spec §22: classify errors).
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
