# POSTMORTEM-003: The rate limiter failed open on every request

- **Date found:** 2026-10-13
- **Severity:** Medium — per-user limits silently disabled; no data at risk
- **Status:** Fixed

## Summary
The gateway's Redis token bucket allowed every request, including the 11th, 12th, … of a burst that should have been
rejected with 429. Redis was healthy. The limiter's calls were timing out during the connection handshake and the
limiter, designed to fail open (ADR-010), did exactly that — on every call.

## Detection
`GatewayApiIT.aUserBurstingPastTheLimitGets429WithRetryAfter` failed: it sent a burst of reservations for one user and
received no 429. The debug log showed the limiter falling back to fail-open on every call.

## Root cause
- `spring.data.redis.timeout` was 50 ms, chosen so that a slow Redis would count as unavailable (spec §23 A).
- Lettuce applies the command timeout to the commands of the connection handshake as well. Establishing the first
  connection (TCP connect plus the RESP handshake) in the containerised test environment took longer than 50 ms, so the
  handshake timed out, the connection was never established, and every limiter call failed.
- Failing open hid the failure from the user, and nothing alerted on fail-open decisions.

## Fix
- `spring.data.redis.timeout: 100ms` for commands and `connect-timeout: 500ms` for establishing connections, in the
  gateway and the inventory service (which uses the same client for its cache).
- Fail-open decisions are counted (`stockforge_rate_limit_decisions_total{outcome}`) and alert after 2 minutes
  (`RateLimiterDegraded`).

## Prevention
- The integration test uses a real Redis container, which is why this was found at all; it now guards the limit.
- `InventoryChaosIT.redisOutageFallsBackToTheDatabase` covers the opposite case: Redis really down, reads still served.

## Lessons
A fail-open path needs a signal. Graceful degradation that is indistinguishable from normal operation is a silent
outage of the protection it degrades.
