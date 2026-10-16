# RUNBOOK-007: Redis unavailable (rate limiter failing open)

**Alert:** `RateLimiterDegraded` — fail-open (or fail-closed) rate-limit decisions for 2 min · **Severity:** warning

## What it means
Redis is down, unreachable or slower than the 100 ms command timeout. By design (ADR-010, NFR-9):
- the gateway allows writes without per-user limits (fail open) — the `rateLimiter` breaker keeps this cheap;
- the inventory service serves catalog and availability reads from PostgreSQL (`productCache` breaker open), with
  `approximate: false`;
- **correctness is unaffected**: stock, purchase limits and idempotency are enforced by PostgreSQL.

What is lost: protection against one user flooding reservations, and read offloading — the database takes all reads.

## Confirm
```bash
docker compose ps redis
docker compose exec redis redis-cli ping
```
```promql
sum by (outcome) (rate(stockforge_rate_limit_decisions_total[1m]))
sum by (result) (rate(stockforge_cache_requests_total[1m]))
```

## Mitigate
1. Restore Redis (`docker compose restart redis`). It holds only caches and token buckets, so starting empty is fine.
2. Under an active abuse attempt while Redis is down, switch the limiter to fail closed:
   `GATEWAY_RATE_LIMIT_FAIL_OPEN=false` and restart the gateway — this rejects all limited writes until Redis is back.
3. Watch `DatabasePoolStarved` (RUNBOOK-004): reads now hit PostgreSQL.

## Evidence
`InventoryChaosIT.redisOutageFallsBackToTheDatabase` cuts Redis with Toxiproxy: reads stay 200, reservations succeed.
