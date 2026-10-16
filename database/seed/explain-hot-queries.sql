-- EXPLAIN (ANALYZE, BUFFERS) for the queries that run constantly or under contention, using the exact SQL of the
-- repositories (spec §13). Run after seed-large-dataset.sql:
--
--   docker compose exec -T postgres psql -U stockforge_admin -d stockforge -f - < database/seed/explain-hot-queries.sql
--
-- Statements that lock (FOR UPDATE SKIP LOCKED) run inside a transaction that is rolled back, so nothing is claimed.
-- The second half re-runs the two worker queries with index scans disabled, to show what the partial indexes buy.

\set ON_ERROR_STOP on
\pset pager off

\echo '=== 1. Expiry worker claim (inventory, every second): partial index idx_reservations_pending_expires_at'
BEGIN;
SET LOCAL search_path = inventory;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id, user_id, product_id, quantity, status, expires_at, created_at, updated_at
FROM reservations
WHERE status = 'PENDING' AND expires_at < now()
ORDER BY expires_at
LIMIT 200
FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo '=== 2. Outbox relay poll (every 200 ms, every service): partial index outbox_events_pending_idx'
BEGIN;
SET LOCAL search_path = inventory;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, topic, aggregate_id, event_type, payload::text AS payload, headers::text AS headers
FROM outbox_events
WHERE status = 'PENDING'
ORDER BY seq
LIMIT 100
FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo '=== 3. Order history, first page (keyset): idx_orders_user_history (user_id, created_at DESC, id)'
SET search_path = ordering;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, user_id, reservation_id, status, saga_step, total_amount, currency, created_at, updated_at
FROM orders WHERE user_id = md5('user-4242')::uuid
ORDER BY created_at DESC, id
LIMIT 21;

\echo '=== 4. Order history, a later page (keyset continues where the last page ended, no OFFSET)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, user_id, reservation_id, status, saga_step, total_amount, currency, created_at, updated_at
FROM orders
WHERE user_id = md5('user-4242')::uuid
  AND (created_at < now() - interval '10 days' OR (created_at = now() - interval '10 days' AND id > md5('x')::uuid))
ORDER BY created_at DESC, id
LIMIT 21;

\echo '=== 5. Saga payment timeout claim (order service): partial index orders_awaiting_payment_idx'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM orders
WHERE saga_step = 'PAYMENT_REQUESTED' AND created_at < now() - make_interval(secs => 120)
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo '=== 6. The hot path: conditional stock decrement on one product (primary key lookup)'
BEGIN;
SET LOCAL search_path = inventory;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
UPDATE inventory
SET available_quantity = available_quantity - 1, reserved_quantity = reserved_quantity + 1,
    version = version + 1, updated_at = now()
WHERE product_id = md5('product-77')::uuid AND available_quantity >= 1;
ROLLBACK;

\echo '=== 7. Reconciliation: reserved counters vs pending reservations (once a minute, whole table)'
SET search_path = inventory;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT i.product_id, COALESCE(p.pending, 0) AS expected, i.reserved_quantity AS actual
FROM inventory i
LEFT JOIN (SELECT product_id, SUM(quantity) AS pending
           FROM reservations WHERE status = 'PENDING'
           GROUP BY product_id) p ON p.product_id = i.product_id
WHERE i.reserved_quantity <> COALESCE(p.pending, 0);

\echo '=== Counterfactual: worker queries without their partial indexes'
BEGIN;
SET LOCAL search_path = inventory;
SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
\echo '--- 1 without the index'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM reservations
WHERE status = 'PENDING' AND expires_at < now()
ORDER BY expires_at LIMIT 200 FOR UPDATE SKIP LOCKED;
\echo '--- 2 without the index'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM outbox_events WHERE status = 'PENDING' ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED;
ROLLBACK;
