-- StockForge invariant check (spec §21, §26). Run as the database owner after a load test has drained, for example:
--   ./scripts/verify-invariants.sh
-- Every query lists violating rows; the final block fails (non-zero exit with ON_ERROR_STOP) if any were found, so
-- CI and load-test runs can gate on it.
--
-- Sections 1-4 must hold at every instant (they are also database constraints or follow from single transactions).
-- Sections 5-7 span services and hold only once the system is quiescent (eventual consistency, spec §15): run them
-- after in-flight sagas have finished.

\set ON_ERROR_STOP on
\pset footer off

CREATE TEMP TABLE violations (check_name TEXT NOT NULL, detail TEXT NOT NULL);

\echo '1. Stock conservation: available + reserved + sold = total, nothing negative'
INSERT INTO violations
SELECT 'stock-conservation', format('product %s: total=%s available=%s reserved=%s sold=%s',
       product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
FROM inventory.inventory
WHERE available_quantity + reserved_quantity + sold_quantity <> total_quantity
   OR LEAST(available_quantity, reserved_quantity, sold_quantity, total_quantity) < 0;

\echo '2. Reserved and sold counters match the reservation rows (no oversell, no leaked reservation)'
INSERT INTO violations
SELECT 'counters-match-reservations', format('product %s: reserved=%s (rows %s), sold=%s (rows %s)',
       i.product_id, i.reserved_quantity, COALESCE(r.pending, 0), i.sold_quantity, COALESCE(r.confirmed, 0))
FROM inventory.inventory i
LEFT JOIN (SELECT product_id,
                  SUM(quantity) FILTER (WHERE status = 'PENDING')   AS pending,
                  SUM(quantity) FILTER (WHERE status = 'CONFIRMED') AS confirmed
           FROM inventory.reservations
           GROUP BY product_id) r ON r.product_id = i.product_id
WHERE i.reserved_quantity <> COALESCE(r.pending, 0)
   OR i.sold_quantity <> COALESCE(r.confirmed, 0);

\echo '3. Purchase limits: the counter equals the user''s pending + confirmed units and never exceeds the limit'
INSERT INTO violations
SELECT 'purchase-limit', format('user %s product %s: active=%s limit=%s rows=%s',
       COALESCE(l.user_id, r.user_id), COALESCE(l.product_id, r.product_id),
       COALESCE(l.active_quantity, 0), l.limit_quantity, COALESCE(r.owned, 0))
FROM inventory.user_product_limits l
FULL JOIN (SELECT user_id, product_id, SUM(quantity) AS owned
           FROM inventory.reservations
           WHERE status IN ('PENDING', 'CONFIRMED')
           GROUP BY user_id, product_id) r ON r.user_id = l.user_id AND r.product_id = l.product_id
WHERE COALESCE(l.active_quantity, 0) <> COALESCE(r.owned, 0)
   OR l.active_quantity > l.limit_quantity;

\echo '4. Expired reservations: nothing PENDING more than a minute past its expiry (the expiry worker keeps up)'
INSERT INTO violations
SELECT 'expiry-backlog', format('reservation %s expired at %s', id, expires_at)
FROM inventory.reservations
WHERE status = 'PENDING' AND expires_at < now() - interval '1 minute';

\echo '5. Orders and reservations agree (quiescent only): a confirmed order owns a confirmed reservation'
INSERT INTO violations
SELECT 'order-reservation', format('order %s is %s but reservation %s is %s',
       o.id, o.status, o.reservation_id, COALESCE(r.status, 'missing'))
FROM ordering.orders o
LEFT JOIN inventory.reservations r ON r.id = o.reservation_id
WHERE (o.status = 'CONFIRMED' AND r.status IS DISTINCT FROM 'CONFIRMED')
   OR (o.status IN ('CANCELLED', 'REFUNDED') AND r.status = 'PENDING');

\echo '6. Orders and payments agree (quiescent only): money is never kept for an order that was not fulfilled'
INSERT INTO violations
SELECT 'order-payment', format('order %s is %s but payment %s is %s', o.id, o.status, p.id, p.status)
FROM ordering.orders o
JOIN payment.payments p ON p.order_id = o.id
WHERE (p.status = 'SUCCEEDED' AND o.status NOT IN ('CONFIRMED', 'REFUND_PENDING'))
   OR (o.status = 'CONFIRMED' AND p.status <> 'SUCCEEDED')
   OR (o.status = 'REFUNDED' AND p.status <> 'REFUNDED');

\echo '7. Outbox: no event waits more than a minute (quiescent only)'
INSERT INTO violations
SELECT 'outbox-stuck', format('%s event %s (%s) pending since %s', schema_name, id, event_type, created_at)
FROM (SELECT 'inventory' AS schema_name, id, event_type, created_at, status FROM inventory.outbox_events
      UNION ALL SELECT 'ordering', id, event_type, created_at, status FROM ordering.outbox_events
      UNION ALL SELECT 'payment', id, event_type, created_at, status FROM payment.outbox_events) e
WHERE status = 'PENDING' AND created_at < now() - interval '1 minute';

\echo ''
\echo 'Violations (empty means all invariants hold):'
SELECT check_name, detail FROM violations ORDER BY check_name LIMIT 100;

DO $$
DECLARE
    found BIGINT;
BEGIN
    SELECT count(*) INTO found FROM violations;
    IF found > 0 THEN
        RAISE EXCEPTION '% invariant violation(s) found', found;
    END IF;
    RAISE NOTICE 'All invariants hold.';
END $$;
