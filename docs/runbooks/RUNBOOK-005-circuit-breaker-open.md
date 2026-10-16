# RUNBOOK-005: Circuit breaker open

**Alert:** `CircuitBreakerOpen` — `resilience4j_circuitbreaker_state{state="open"} == 1` for 1 min · **Severity:** critical

## What it means
| Breaker | Where | Opens when | While open |
| :--- | :--- | :--- | :--- |
| `inventory`, `order` | api-gateway → gRPC | ≥ 50 % of the last 50 calls failed with `UNAVAILABLE`/`DEADLINE_EXCEEDED`/`INTERNAL`, or 80 % slower than 1.5 s | gateway answers 503 + Retry-After at once |
| `inventory` | order-service → inventory gRPC | inventory unreachable | `POST /orders` answers 503 |
| `productCache` | inventory → Redis | Redis errors or calls slower than 50 ms | reads go straight to PostgreSQL |
| `rateLimiter` | gateway → Redis | Redis unreachable | limiter fails open (RUNBOOK-007) |

Business outcomes (sold out, not found, limit exceeded) never count as failures, so a sold-out product cannot open a
breaker. After 5 s the breaker lets 5 trial calls through (half-open) and closes if they succeed.

## Confirm
```promql
max by (application, name, state) (resilience4j_circuitbreaker_state)
sum by (application, name, kind) (rate(resilience4j_circuitbreaker_calls_seconds_count[1m]))
```
Then follow RUNBOOK-009 for the downstream service, or RUNBOOK-007 for Redis.

## Mitigate
Fix the dependency; the breaker closes on its own. Do not disable breakers to "get traffic through" — they are what
keeps the gateway's threads and the database's connections free while a dependency is down.
