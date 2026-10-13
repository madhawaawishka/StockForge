# RUNBOOK-001: Stock Invariant Violation

## Severity: CRITICAL (P1 Incident)

## 1. Description
The `StockInvariantViolation` alert fired because `stockforge_inventory_invariant_violations > 0`. This indicates that physical inventory stock conservation (`available_quantity + reserved_quantity + sold_quantity == total_physical_quantity`) was broken, or `available_quantity < 0` for one or more SKUs.

## 2. Immediate Impact
Potential overselling, revenue loss, and inventory corruption. Customers may have reserved or purchased items that do not exist in the physical warehouse.

## 3. Immediate Mitigation Steps (Triage)
1. **Identify the Corrupted SKU:**
   Connect to the PostgreSQL database and run the diagnostic reconciliation query:
   ```sql
   SELECT product_id, sku, available_quantity, reserved_quantity, sold_quantity, total_physical_quantity,
          (available_quantity + reserved_quantity + sold_quantity) AS calculated_total
   FROM inventory.inventory
   JOIN inventory.products USING (product_id)
   WHERE (available_quantity + reserved_quantity + sold_quantity) != total_physical_quantity
      OR available_quantity < 0;
   ```
2. **Halt Purchases on Affected SKU (Circuit Breaker):**
   Temporarily deactivate the affected product to halt incoming reservations:
   ```sql
   UPDATE inventory.products SET status = 'INACTIVE' WHERE product_id = '<CORRUPTED_PRODUCT_ID>';
   ```
   Evict product and inventory caches from Redis:
   ```bash
   redis-cli DEL "stockforge:product:<CORRUPTED_PRODUCT_ID>"
   redis-cli DEL "stockforge:inventory:<CORRUPTED_PRODUCT_ID>"
   ```
3. **Audit Active Reservations:**
   Inspect active reservations for the product:
   ```sql
   SELECT reservation_id, user_id, quantity, status, expires_at
   FROM inventory.reservations
   WHERE product_id = '<CORRUPTED_PRODUCT_ID>' AND status = 'PENDING';
   ```

## 4. Root Cause Analysis
- Check if manual database modifications bypassed the application layer.
- Verify whether an unhandled race condition occurred in `AtomicConditionalUpdateReservationStrategy`.
- Review audit logs in `inventory_audit_log` around the violation timestamp.

## 5. Recovery & Rollback
1. Correct the stock counts in `inventory.inventory` to match physical truth.
2. If excess pending reservations exist, cancel them before expiry:
   ```sql
   UPDATE inventory.reservations SET status = 'CANCELLED' WHERE reservation_id = '<EXCESS_RESERVATION_ID>';
   ```
3. Re-enable product:
   ```sql
   UPDATE inventory.products SET status = 'ACTIVE' WHERE product_id = '<CORRUPTED_PRODUCT_ID>';
   ```
4. Verify reconciliation gauge reset:
   ```sql
   SELECT stockforge_inventory_invariant_violations;
   ```
