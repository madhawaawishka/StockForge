package com.stockforge.inventory.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Cross-checks of the inventory invariants straight from the database (spec §21 reconciliation). A non-empty result is
 * a bug to investigate, never something to "fix" silently.
 */
public interface InventoryReconciliation {

    /** Products where available + reserved + sold != total. The CHECK constraint should make this impossible. */
    List<Discrepancy> stockConservationViolations();

    /** Products whose reserved quantity differs from the sum of their PENDING reservations. */
    List<Discrepancy> reservedQuantityMismatches();

    /** User counters that differ from the units in that user's PENDING and CONFIRMED reservations. */
    List<Discrepancy> purchaseLimitCounterMismatches();

    /** Units of {@code expected} that the stored {@code actual} value should equal. */
    record Discrepancy(UUID productId, String subject, long expected, long actual) {}
}
