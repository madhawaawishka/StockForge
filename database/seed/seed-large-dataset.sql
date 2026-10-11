-- StockForge Large-Dataset Seed Generator (spec §13, W3-04)
-- Generates realistic volumes to validate indexes and EXPLAIN ANALYZE query plans.
-- Run against stockforge database:
--   psql -U postgres -d stockforge -f database/seed/seed-large-dataset.sql

\set ON_ERROR_STOP on

BEGIN;

-- 1. Inventory schema: 100,000 products and inventory rows
\echo 'Seeding 100,000 products in inventory schema...'
INSERT INTO inventory.products (id, sku, name, description, price_amount, price_currency, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'SKU-PROD-' || lpad(i::text, 7, '0'),
    'Product ' || i,
    'High-demand inventory test item ' || i,
    (10 + (i % 990))::numeric(19,4),
    'USD',
    now() - (i || ' seconds')::interval,
    now()
FROM generate_series(1, 100000) AS s(i);

\echo 'Seeding corresponding inventory rows...'
INSERT INTO inventory.inventory (product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version, updated_at)
SELECT
    id,
    1000,
    950,
    30,
    20,
    0,
    now()
FROM inventory.products;

-- 2. Inventory schema: 200,000 reservations with realistic status distribution
\echo 'Seeding 200,000 reservations (PENDING, CONFIRMED, EXPIRED, CANCELLED)...'
WITH sample_products AS (
    SELECT id, row_number() OVER () AS rn
    FROM inventory.products
    LIMIT 20000
)
INSERT INTO inventory.reservations (id, product_id, user_id, quantity, status, expires_at, created_at, updated_at)
SELECT
    gen_random_uuid(),
    p.id,
    gen_random_uuid(),
    (1 + (i % 2)),
    CASE (i % 4)
        WHEN 0 THEN 'PENDING'
        WHEN 1 THEN 'CONFIRMED'
        WHEN 2 THEN 'EXPIRED'
        ELSE 'CANCELLED'
    END,
    CASE (i % 4)
        WHEN 0 THEN now() + interval '10 minutes'
        ELSE now() - interval '30 minutes'
    END,
    now() - interval '1 hour',
    now()
FROM generate_series(1, 200000) AS s(i)
JOIN sample_products p ON p.rn = ((i % 20000) + 1);

-- 3. Ordering schema: 500,000 orders
\echo 'Seeding 500,000 orders in ordering schema...'
INSERT INTO ordering.orders (id, user_id, reservation_id, status, total_amount, currency, created_at, updated_at)
SELECT
    gen_random_uuid(),
    gen_random_uuid(),
    gen_random_uuid(),
    CASE (i % 4)
        WHEN 0 THEN 'CONFIRMED'
        WHEN 1 THEN 'PENDING_PAYMENT'
        WHEN 2 THEN 'CANCELLED'
        ELSE 'REFUNDED'
    END,
    (49.99 + (i % 200))::numeric(19,4),
    'USD',
    now() - ((i % 100000) || ' minutes')::interval,
    now()
FROM generate_series(1, 500000) AS s(i);

\echo 'Seeding order items...'
INSERT INTO ordering.order_items (order_id, product_id, quantity, unit_price, currency)
SELECT
    o.id,
    gen_random_uuid(),
    1,
    o.total_amount,
    'USD'
FROM ordering.orders o
LIMIT 500000;

-- 4. Outbox events: 100,000 outbox records
\echo 'Seeding outbox events in ordering schema...'
INSERT INTO ordering.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, status, attempts, created_at)
SELECT
    gen_random_uuid(),
    'Order',
    gen_random_uuid()::text,
    'OrderCreated',
    '{"sample": true}'::jsonb,
    gen_random_uuid()::text,
    CASE WHEN i % 20 = 0 THEN 'PENDING' ELSE 'PUBLISHED' END,
    0,
    now() - (i || ' seconds')::interval
FROM generate_series(1, 100000) AS s(i);

COMMIT;

\echo 'Analyzing tables to update PostgreSQL statistics...'
ANALYZE inventory.products;
ANALYZE inventory.inventory;
ANALYZE inventory.reservations;
ANALYZE ordering.orders;
ANALYZE ordering.order_items;
ANALYZE ordering.outbox_events;

\echo 'Large dataset seeding complete!'
