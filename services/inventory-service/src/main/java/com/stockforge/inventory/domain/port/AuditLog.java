package com.stockforge.inventory.domain.port;

import java.util.Map;

/** Append-only record of administrative actions (spec §28, FR-10). Written in the transaction of the action. */
public interface AuditLog {

    void record(String actorId, String action, String aggregateType, String aggregateId, Map<String, Object> details);
}
