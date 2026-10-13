-- StockForge Business Invariant Verification Script
-- Run against stockforge PostgreSQL database to assert zero violations.

\echo '=== 1. Checking Stock Conservation Invariant (Physical vs Sum of States) ==='
SELECT product_id,
       available_quantity,
       reserved_quantity,
       sold_quantity,
       total_physical_quantity,
       (available_quantity + reserved_quantity + sold_quantity) AS sum_states,
       (total_physical_quantity - (available_quantity + reserved_quantity + sold_quantity)) AS delta
FROM inventory.inventory
WHERE (available_quantity + reserved_quantity + sold_quantity) != total_physical_quantity;

\echo '=== 2. Checking Negative Quantity Invariants ==='
SELECT product_id, available_quantity, reserved_quantity, sold_quantity
FROM inventory.inventory
WHERE available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0;

\echo '=== 3. Checking Active Reservations vs Inventory Reserved Counter ==='
WITH active_reservations AS (
    SELECT product_id, COALESCE(SUM(quantity), 0) AS actual_reserved
    FROM inventory.reservations
    WHERE status = 'PENDING'
    GROUP BY product_id
)
SELECT i.product_id,
       i.reserved_quantity AS counter_reserved,
       COALESCE(ar.actual_reserved, 0) AS active_reservations_sum,
       (i.reserved_quantity - COALESCE(ar.actual_reserved, 0)) AS discrepancy
FROM inventory.inventory i
LEFT JOIN active_reservations ar ON i.product_id = ar.product_id
WHERE i.reserved_quantity != COALESCE(ar.actual_reserved, 0);

\echo '=== 4. Checking Duplicate Reservation Idempotency Keys ==='
SELECT user_id, idempotency_key, COUNT(*) AS duplicate_count
FROM inventory.reservations
GROUP BY user_id, idempotency_key
HAVING COUNT(*) > 1;

\echo '=== 5. Checking Order 1:1 Reservation Invariant ==='
SELECT reservation_id, COUNT(*) AS order_count
FROM ordering.orders
WHERE reservation_id IS NOT NULL
GROUP BY reservation_id
HAVING COUNT(*) > 1;

\echo '=== 6. Checking Stuck Outbox Events (>5 minutes) ==='
SELECT 'inventory' AS service, id, event_type, status, created_at
FROM inventory.outbox_events
WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '5 minutes'
UNION ALL
SELECT 'ordering' AS service, id, event_type, status, created_at
FROM ordering.outbox_events
WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '5 minutes'
UNION ALL
SELECT 'payment' AS service, id, event_type, status, created_at
FROM payment.outbox_events
WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '5 minutes';
