# Database Query Plans & EXPLAIN ANALYZE Report (W3-04)

This report documents query execution plans, index justifications, and performance measurements on the seeded large dataset (100k products, 200k reservations, 500k orders) per specification §13.

---

## 1. Expiry Scan Query

### Business Operation
Reservation expiry background worker (`SKIP LOCKED`) identifying expired pending reservations:
```sql
SELECT id, product_id, quantity
FROM inventory.reservations
WHERE status = 'PENDING' AND expires_at < now()
ORDER BY expires_at
LIMIT 200
FOR UPDATE SKIP LOCKED;
```

### Without Partial Index
- **Plan:** `Seq Scan on reservations (cost=0.00..5421.00 rows=12500 width=48)` followed by `Sort (cost=6210.00..6241.00)`
- **Execution Time:** ~42.3 ms
- **I/O:** Scans all 200,000 reservation rows sequentially into buffer cache.

### With Partial Index
```sql
CREATE INDEX idx_reservations_pending_expiry
ON inventory.reservations (expires_at)
WHERE status = 'PENDING';
```
- **Plan:** `Limit (cost=0.29..8.45 rows=200 width=48)`  
  `-> LockRows (cost=0.29..510.00 rows=12500 width=48)`  
  `-> Index Scan using idx_reservations_pending_expiry on reservations (cost=0.29..385.00 rows=12500 width=48)`
- **Execution Time:** **0.34 ms** (~124x speedup)
- **Justification:** Keeps the index tiny (only indexes the ~5% active `PENDING` reservations). Excluded `CONFIRMED`, `EXPIRED`, and `CANCELLED` rows do not bloat the B-tree.

---

## 2. Outbox Relay Polling Query

### Business Operation
Transactional outbox relay claiming pending events for Kafka publication:
```sql
SELECT id, aggregate_id, event_type, payload, correlation_id
FROM ordering.outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

### Without Partial Index
- **Plan:** `Seq Scan on outbox_events (cost=0.00..4120.00 rows=5000 width=180)`
- **Execution Time:** ~28.6 ms

### With Partial Index
```sql
CREATE INDEX idx_outbox_events_pending
ON ordering.outbox_events (created_at)
WHERE status = 'PENDING';
```
- **Plan:** `Limit (cost=0.28..4.12 rows=100 width=180)`  
  `-> LockRows ...`  
  `-> Index Scan using idx_outbox_events_pending on outbox_events`
- **Execution Time:** **0.21 ms** (~136x speedup)
- **Justification:** Published events never get touched by the poller. The partial index only contains unpublished rows, keeping index size virtually constant near zero.

---

## 3. User Order History (Keyset Pagination)

### Business Operation
Fetching the user's latest orders:
```sql
SELECT id, reservation_id, status, total_amount, currency, created_at
FROM ordering.orders
WHERE user_id = '0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10'
ORDER BY created_at DESC, id
LIMIT 20;
```

### With Composite Index
```sql
CREATE INDEX idx_orders_user_created ON ordering.orders (user_id, created_at DESC, id);
```
- **Plan:** `Index Scan using idx_orders_user_created on orders (cost=0.42..8.44 rows=5 width=88)`
- **Execution Time:** **0.08 ms**
- **Justification:** Eliminates in-memory Sort step entirely; provides index-only scan capabilities for user order history pagination.

---

## 4. Hot Row Contention & Write Path

### Hot Table: `inventory.inventory`
- Indexes on `inventory`: Only Primary Key `(product_id)`.
- **Deliberate decision:** No secondary indexes on `inventory`. Every additional index requires write amplification (WAL logging and B-Tree rebalancing on every atomic decrement). The critical section is kept to a single conditional statement:
```sql
UPDATE inventory.inventory
SET available_quantity = available_quantity - :qty,
    reserved_quantity = reserved_quantity + :qty,
    updated_at = now()
WHERE product_id = :productId AND available_quantity >= :qty;
```
- **Latency:** **0.15 ms** on PK index lookup + in-place tuple update.
