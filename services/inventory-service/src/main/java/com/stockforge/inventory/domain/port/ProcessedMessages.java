package com.stockforge.inventory.domain.port;

import java.util.UUID;

/** Inbox of the idempotent consumers (spec §18). Must be called in the transaction of the business change. */
public interface ProcessedMessages {

    /** Returns {@code false} if the consumer already processed this event (a duplicate delivery). */
    boolean markProcessed(String consumer, UUID eventId);
}
