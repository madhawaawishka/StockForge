package com.stockforge.inventory.application;

import com.stockforge.inventory.domain.port.InventoryReconciliation;
import com.stockforge.inventory.domain.port.InventoryReconciliation.Discrepancy;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic cross-check of the inventory invariants (spec §21). It reports; it never repairs: a mismatch is a bug, and
 * silently "fixing" the numbers would destroy the evidence needed to find it.
 */
public class ReconcileInventoryUseCase {

    private final InventoryReconciliation reconciliation;

    public ReconcileInventoryUseCase(InventoryReconciliation reconciliation) {
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
    }

    /** Runs all checks against one consistent snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report reconcile() {
        return new Report(
                reconciliation.stockConservationViolations(),
                reconciliation.reservedQuantityMismatches(),
                reconciliation.purchaseLimitCounterMismatches());
    }

    /** Result of one reconciliation run; {@link #violations()} must always be zero. */
    public record Report(
            List<Discrepancy> conservationViolations,
            List<Discrepancy> reservedMismatches,
            List<Discrepancy> purchaseLimitMismatches) {

        public Report {
            conservationViolations = List.copyOf(conservationViolations);
            reservedMismatches = List.copyOf(reservedMismatches);
            purchaseLimitMismatches = List.copyOf(purchaseLimitMismatches);
        }

        public int violations() {
            return conservationViolations.size() + reservedMismatches.size() + purchaseLimitMismatches.size();
        }
    }
}
