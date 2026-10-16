# RUNBOOK-001: Stock invariant violation

**Alert:** `StockInvariantViolation` — `stockforge_inventory_invariant_violations > 0` · **Severity:** critical (P1)

## What it means
The inventory service's reconciliation job (every minute, `REPEATABLE READ` snapshot) found rows where
- `available + reserved + sold ≠ total`, or a counter is negative — *cannot* happen while the CHECK constraint exists,
  so this indicates a dropped constraint or manual data change;
- `reserved_quantity` differs from the sum of `PENDING` reservations — a reservation transition updated one but not
  the other;
- a user's purchase-limit counter differs from their pending + confirmed units (POSTMORTEM-005).

Overselling may have happened. Treat as an incident until proven otherwise.

## Confirm
```bash
./scripts/verify-invariants.sh     # lists every violating row by check
```
```sql
-- Which flows touched the product recently?
SELECT action, aggregate_id, actor_id, created_at, metadata
FROM inventory.audit_logs WHERE aggregate_id = '<product-id>' ORDER BY created_at DESC LIMIT 20;
SELECT status, count(*), sum(quantity) FROM inventory.reservations
WHERE product_id = '<product-id>' GROUP BY status;
```

## Mitigate
1. **Stop selling the product** if `available` may be wrong: adjust its available stock to zero through the admin
   API (`POST /api/v1/admin/products/{id}/stock-adjustments`, audited). Do not edit rows by hand.
2. If the reserved counter drifted, find the reservation transition that skipped it (logs by `reservationId`, trace in
   Tempo) before correcting anything.
3. Correct the counter with a stock adjustment that states the reason; the adjustment is audited and idempotent.

## Follow-up
Write a postmortem. Add the failing scenario as a test in `ReservationLifecycleTest` or the concurrency suite —
every invariant must be enforced in the domain, the database and a test (CLAUDE.md).
