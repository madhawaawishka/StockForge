# ADR-010: The gateway's rate limiter fails open when Redis is unavailable
- Status: Accepted
- Date: 2026-10-13

## Context
Per-user token buckets in Redis protect the write endpoints from bursts and bots (spec §23 C). Redis is not part of
the correctness path: the database enforces stock, purchase limits and idempotency on its own (NFR-9). The question is
what the gateway does when Redis is down or slow.

## Options considered
1. **Fail closed** — reject every write while Redis is unavailable. A cache outage becomes a full sales outage.
2. **Fail open** — allow the request without a limit decision.
3. **Local fallback limiter** — per-instance in-memory buckets. Limits then scale with the number of gateway instances
   and differ per instance; more code for a degraded mode.

## Decision
Fail open by default (`gateway.rate-limit.fail-open=true`), behind the `rateLimiter` circuit breaker so an outage
costs one fast failure per cool-down instead of a Redis timeout on every request. Redis calls are bounded by a 100 ms
command timeout (`spring.data.redis.timeout`). Fail-open decisions are counted
(`stockforge_rate_limit_decisions_total{outcome="fail_open"}`) and alerted on (`RateLimiterDegraded`).
Operators can switch to fail-closed with one environment variable during an attack.

## Consequences
- A Redis outage temporarily removes per-user limits; overselling is still impossible because the database
  enforces every invariant.
- Bulkheads (in-flight call caps per downstream service) still shed excess load with 503 while limits are off.
- The 100 ms timeout must cover the TCP/RESP handshake as well as commands: Lettuce applies the command timeout to
  the connection handshake, which a 50 ms value did not survive (POSTMORTEM-003).

## Evidence
- `GatewayApiIT` exercises the limiter against a real Redis: the 11th burst request gets 429 + Retry-After.
- `ResilientGrpcCallTest` covers the breaker and bulkhead behaviour of downstream calls.
