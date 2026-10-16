# Query plans on a large dataset

`EXPLAIN (ANALYZE, BUFFERS)` of the queries that run constantly (workers, relays) or under contention (the hot path),
captured on 2026-10-14 with PostgreSQL 18.6 in Docker (environment in [README.md](README.md)) after loading
[`database/seed/seed-large-dataset.sql`](../../database/seed/seed-large-dataset.sql):

| Table | Rows |
| :--- | ---: |
| `inventory.products`, `inventory.inventory` | 100,036 |
| `inventory.reservations` | ≈ 562,000 |
| `inventory.user_product_limits` | ≈ 350,000 |
| `inventory.outbox_events` | ≈ 1,150,000 (almost all `PUBLISHED`) |
| `ordering.orders` | ≈ 325,000 (20,011 users) |

Reproduce with [`database/seed/explain-hot-queries.sql`](../../database/seed/explain-hot-queries.sql), which uses the
repositories' exact SQL and rolls back every locking statement.

## Summary
| # | Query | Runs | Plan | Execution |
| :---: | :--- | :--- | :--- | ---: |
| 1 | expiry worker claim | every second | index scan on partial index `idx_reservations_pending_expires_at` | **0.80 ms** |
| 2 | outbox relay poll | every 200 ms per service | index scan on partial index `outbox_events_pending_idx` | **0.38 ms** |
| 3 | order history, first page | per request | bitmap scan on `idx_orders_user_history` + sort of 16 rows | **0.62 ms** |
| 4 | order history, later page (keyset) | per request | same index, bounded by `(created_at, id)` | **0.04 ms** |
| 5 | saga payment-timeout claim | every few seconds | index scan on partial index `orders_awaiting_payment_idx` | **0.03 ms** |
| 6 | conditional stock decrement (hot path) | every reservation | primary-key index scan | **0.25 ms** |
| 7 | reconciliation (reserved vs pending) | every minute | hash join over the whole inventory table | **48.6 ms** |

## What the partial indexes buy
The two worker queries look for a tiny, moving subset of a large table: pending reservations past their expiry,
unpublished outbox events. A partial index contains only that subset, so its size — and the query's cost — does not
grow with history. Re-running them with index scans disabled shows the alternative:

| Query | With partial index | Without (sequential scan) | Rows read without |
| :--- | ---: | ---: | ---: |
| 1 expiry claim | 0.80 ms, 123 buffers | 55.3 ms, 15,185 buffers | 562,107 |
| 2 outbox poll | 0.38 ms, 69 buffers | 328.2 ms, 40,801 buffers | 1,149,214 |

Every service polls its outbox five times a second; without the partial index, a week of history would cost each
service more than a second and a half of sequential scanning per second of wall time.

## Plans
**1. Expiry worker claim** — `WHERE status = 'PENDING' AND expires_at < now() ORDER BY expires_at LIMIT 200 FOR UPDATE
SKIP LOCKED`
```
Limit (actual time=0.765..0.765 rows=0.00 loops=1)
  ->  LockRows
        ->  Index Scan using idx_reservations_pending_expires_at on reservations
              Index Cond: (expires_at < now())
              Filter: (status = 'PENDING'::text)
              Buffers: shared hit=123
Execution Time: 0.796 ms
```
The index is already in `expires_at` order, so `ORDER BY … LIMIT` stops after the batch; `SKIP LOCKED` lets several
workers claim disjoint batches. (The 25,000 expired reservations in the seed had been drained by the running expiry
worker — at about 700 per second — by the time this plan was captured, hence 0 rows.)

**2. Outbox relay poll** — `WHERE status = 'PENDING' ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED`
```
Limit (actual time=0.365..0.365 rows=0.00 loops=1)
  ->  LockRows
        ->  Index Scan using outbox_events_pending_idx on outbox_events
              Buffers: shared hit=69
Execution Time: 0.380 ms
```

**3–4. Order history with keyset pagination** — `WHERE user_id = :u [AND (created_at, id) after the cursor] ORDER BY
created_at DESC, id LIMIT 21`
```
Limit (actual time=0.586..0.588 rows=16.00 loops=1)
  ->  Sort  Sort Key: created_at DESC, id   Sort Method: quicksort  Memory: 27kB
        ->  Bitmap Heap Scan on orders
              ->  Bitmap Index Scan on idx_orders_user_history
                    Index Cond: (user_id = '2b966af9-…'::uuid)
Execution Time: 0.618 ms
```
With 16 orders for this user the planner prefers a bitmap scan and a tiny sort over walking the index in order; for a
user with thousands of orders the same index provides the order directly. Later pages are bounded by the cursor
instead of `OFFSET`, so page 1,000 costs the same as page 1 (0.04 ms above).

**6. The hot path**
```
Update on inventory (actual time=0.141..0.141 rows=0.00 loops=1)
  ->  Index Scan using inventory_pkey on inventory
        Index Cond: (product_id = '1f05c826-…'::uuid)
        Filter: (available_quantity >= 1)
Execution Time: 0.248 ms
```
The statement is a primary-key lookup; its cost under contention is the wait for the row lock, not the plan
(see [concurrency-strategies.md](concurrency-strategies.md)).

**7. Reconciliation**
```
Hash Left Join (actual time=47.915..47.919 rows=0.00 loops=1)
  Filter: (i.reserved_quantity <> COALESCE(p.pending, '0'::bigint))
  ->  Seq Scan on inventory i  (rows=100036)
  ->  Hash
        ->  HashAggregate  Group Key: reservations.product_id
              ->  Index Scan using idx_reservations_pending_expires_at on reservations  (rows=25000)
Execution Time: 48.590 ms
```
A full pass over 100,000 products in under 50 ms, once a minute, in a `REPEATABLE READ` snapshot that does not block
writers. The aggregation reuses the pending-only partial index instead of scanning half a million reservations.

## Indexes deliberately not added
- No index on `inventory.available_quantity`: the hot table is updated on every reservation, and every extra index is
  extra write work and a HOT-update blocker (spec §13).
- No index on `outbox_events.created_at` for published rows beyond `outbox_events_published_idx`, which only serves
  the retention job.
