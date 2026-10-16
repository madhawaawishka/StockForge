package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.port.ProcessedMessages;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Fake inbox. */
public class InMemoryProcessedMessages implements ProcessedMessages {

    private final Set<String> processed = new HashSet<>();

    @Override
    public synchronized boolean markProcessed(String consumer, UUID eventId) {
        return processed.add(consumer + "/" + eventId);
    }
}
