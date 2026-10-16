package com.stockforge.order.domain.port;

/**
 * Moves dead-lettered messages back to their original topic so the owning consumer processes them again (spec §22).
 * Safe because every consumer is idempotent: a message that was in fact processed before is skipped by its inbox.
 */
public interface DeadLetterReplayer {

    /** Replays at most {@code maxMessages} messages from {@code <topic>.DLT}; returns how many were replayed. */
    int replay(String topic, int maxMessages);
}
