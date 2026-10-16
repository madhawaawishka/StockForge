-- Large-dataset seed for query-plan work (spec §13, W3-04): realistic volumes so EXPLAIN shows the plans production
-- would get. Run as the database owner against a *disposable* local database, after the services have migrated it:
--
--   docker compose exec -T postgres psql -U stockforge_admin -d stockforge -f - < database/seed/seed-large-dataset.sql
--
-- Only rows of the seeded products (SKU prefix SEED-) are touched, so it can run next to data from other tests.
-- Volumes: 100,000 products · 500,000 reservations (20,011 users) · ~350,000 orders · 1,000,000 published outbox
-- events. Every invariant checked by scripts/check-invariants.sql still holds afterwards: counters are derived from the
-- seeded rows, each (user, product) pair has one reservation, and orders point at matching reservations.
-- About 25,000 reservations are seeded already expired, so the expiry worker has a real backlog to work through.

\set ON_ERROR_STOP on
\timing on

BEGIN;

\echo 'products and inventory rows'
INSERT INTO inventory.products (id, sku, name, price_amount, currency, status, created_at, updated_at)
SELECT md5('product-' || p)::uuid,
       'SEED-' || lpad(p::text, 7, '0'),
       'Seeded product ' || p,
       (10 + p % 990)::numeric(19, 4),
       'USD',
       'ACTIVE',
       now() - interval '90 days',
       now() - interval '90 days'
FROM generate_series(1, 100000) AS p;

INSERT INTO inventory.inventory (product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity,
                                 version, updated_at)
SELECT md5('product-' || p)::uuid, 1000, 1000, 0, 0, 0, now() - interval '90 days'
FROM generate_series(1, 100000) AS p;

\echo 'reservations: 5% pending, 5% pending but past expiry, 60% confirmed, 20% cancelled, 10% expired'
-- user = i mod 20011 and product = i * 7919 mod 100000 never repeat a (user, product) pair within 500,000 rows,
-- so the purchase limit (2 units) holds by construction.
INSERT INTO inventory.reservations (id, user_id, product_id, quantity, status, expires_at, created_at, updated_at)
SELECT md5('reservation-' || i)::uuid,
       md5('user-' || (i % 20011))::uuid,
       md5('product-' || (1 + (i::bigint * 7919) % 100000))::uuid,
       1 + i % 2,
       CASE WHEN i % 20 IN (0, 1) THEN 'PENDING'
            WHEN i % 20 BETWEEN 2 AND 13 THEN 'CONFIRMED'
            WHEN i % 20 BETWEEN 14 AND 17 THEN 'CANCELLED'
            ELSE 'EXPIRED' END,
       created + interval '10 minutes',
       created,
       created + interval '1 minute'
FROM (SELECT i,
             CASE WHEN i % 20 = 0 THEN now() - interval '2 minutes'           -- still holding stock
                  ELSE now() - (i % 2592000) * interval '1 second' - interval '1 hour' END AS created
      FROM generate_series(1, 500000) AS i) AS r;

-- Statistics for the rows just inserted (ANALYZE sees this transaction's rows): without them the joins below are
-- planned for empty tables and run as nested loops over 500,000 x 100,000 rows.
ANALYZE inventory.products, inventory.reservations;

\echo 'stock counters and purchase-limit counters derived from the reservations'
UPDATE inventory.inventory i
SET reserved_quantity = s.pending,
    sold_quantity     = s.confirmed,
    total_quantity    = i.available_quantity + s.pending + s.confirmed
FROM (SELECT product_id,
             COALESCE(SUM(quantity) FILTER (WHERE status = 'PENDING'), 0)   AS pending,
             COALESCE(SUM(quantity) FILTER (WHERE status = 'CONFIRMED'), 0) AS confirmed
      FROM inventory.reservations
      WHERE product_id IN (SELECT id FROM inventory.products WHERE sku LIKE 'SEED-%')
      GROUP BY product_id) s
WHERE s.product_id = i.product_id;

INSERT INTO inventory.user_product_limits (user_id, product_id, active_quantity, limit_quantity, updated_at)
SELECT r.user_id, r.product_id, r.quantity, 2, r.updated_at
FROM inventory.reservations r
JOIN inventory.products p ON p.id = r.product_id AND p.sku LIKE 'SEED-%'
WHERE r.status IN ('PENDING', 'CONFIRMED');

\echo 'orders: one per confirmed reservation, plus cancelled orders for a quarter of the cancelled reservations'
INSERT INTO ordering.orders (id, user_id, reservation_id, status, saga_step, total_amount, currency, created_at,
                             updated_at)
SELECT md5('order-' || r.id)::uuid,
       r.user_id,
       r.id,
       CASE r.status WHEN 'CONFIRMED' THEN 'CONFIRMED' ELSE 'CANCELLED' END,
       'COMPLETED',
       p.price_amount * r.quantity,
       'USD',
       r.created_at + interval '10 seconds',
       r.updated_at
FROM inventory.reservations r
JOIN inventory.products p ON p.id = r.product_id AND p.sku LIKE 'SEED-%'
WHERE r.status = 'CONFIRMED'
   OR (r.status = 'CANCELLED' AND get_byte(decode(md5(r.id::text), 'hex'), 0) < 64);

INSERT INTO ordering.order_items (order_id, product_id, quantity, unit_price, currency)
SELECT o.id, r.product_id, r.quantity, o.total_amount / r.quantity, 'USD'
FROM ordering.orders o
JOIN inventory.reservations r ON r.id = o.reservation_id
JOIN inventory.products p ON p.id = r.product_id AND p.sku LIKE 'SEED-%';

\echo 'outbox history: 1,000,000 published events over the 7-day retention window'
INSERT INTO inventory.outbox_events (id, topic, aggregate_type, aggregate_id, event_type, payload, headers, status,
                                     attempts, created_at, published_at)
SELECT md5('event-' || e)::uuid,
       'inventory-events',
       'Reservation',
       md5('reservation-' || e)::uuid::text,
       'ReservationCreated',
       '{}'::jsonb,
       '{}'::jsonb,
       'PUBLISHED',
       1,
       now() - (e % 600000) * interval '1 second',
       now() - (e % 600000) * interval '1 second' + interval '50 milliseconds'
FROM generate_series(1, 1000000) AS e;

COMMIT;

ANALYZE inventory.products, inventory.inventory, inventory.reservations, inventory.user_product_limits,
        inventory.outbox_events, ordering.orders, ordering.order_items;

\echo 'done — run scripts/check-invariants.sql to confirm nothing is inconsistent'
