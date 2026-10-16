package com.stockforge.inventory.testsupport;

import com.stockforge.inventory.domain.port.AuditLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records audit entries for assertions. */
public class InMemoryAuditLog implements AuditLog {

    public record Entry(
            String actorId, String action, String aggregateType, String aggregateId, Map<String, Object> details) {}

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public synchronized void record(
            String actorId, String action, String aggregateType, String aggregateId, Map<String, Object> details) {
        entries.add(new Entry(actorId, action, aggregateType, aggregateId, Map.copyOf(details)));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }
}
