# RUNBOOK-004: Database pool starved / reservations too slow

**Alerts:**
- `DatabasePoolStarved` — requests waiting for a connection (`hikaricp_connections_pending > 0`) for 1 min · critical
- `ReservationLatencySloBurn` — too many reservations slower than 300 ms (NFR-3) · critical

## What it means
Each service has a small, fixed pool (10 connections; spec §24). When every connection is busy, requests queue for up
to `connection-timeout` (1 s) and then fail fast with 503 + Retry-After. Typical causes: a slow query, lock contention
on a hot product, a long transaction, or more instances than the database can serve.

## Confirm
```promql
max by (application) (hikaricp_connections_active)
max by (application) (hikaricp_connections_acquire_seconds_max)
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="api-gateway",uri="/api/v1/reservations"}[5m])))
```
```sql
-- What is running and what is waiting?
SELECT pid, usename, state, wait_event_type, wait_event, now() - xact_start AS xact_age, left(query, 100)
FROM pg_stat_activity WHERE datname = 'stockforge' AND state <> 'idle' ORDER BY xact_age DESC;
-- Slowest statements since the last reset
SELECT calls, round(mean_exec_time::numeric, 2) AS mean_ms, left(query, 100)
FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;
```
Many sessions in `Lock / transactionid` on one inventory row = hot-row contention (expected in a flash sale; the
atomic conditional update keeps each lock very short). Waits longer than `lock_timeout` (2 s) are cancelled
(SQLSTATE `55P03`) and answered 503 + Retry-After; slow commits — a saturated disk — lengthen every lock and show up here
first. Sessions `idle in transaction` = a transaction left open
(the server kills them after 10 s).

## Mitigate
- A runaway query: `SELECT pg_cancel_backend(<pid>);`. Statement timeout (5 s) and lock timeout (2 s) bound the damage.
- Hot product: rate limits and bulkheads shed the excess; do **not** raise the pool size — more connections fighting
  for one row lock is slower, not faster (docs/benchmarks/connection-pool-sizing.md).
- If the pessimistic or optimistic strategy is configured, switch back to `ATOMIC_CONDITIONAL_UPDATE`
  (`INVENTORY_RESERVATION_STRATEGY`).

## Follow-up
Capture `EXPLAIN (ANALYZE, BUFFERS)` for the slow statement and add the result to docs/benchmarks/query-plans.md.
