# POSTMORTEM-006: A database partition was answered with 500 instead of 503

- **Date found:** 2026-10-14
- **Severity:** Low — correct data, wrong signal to clients
- **Status:** Fixed

## Summary
When PostgreSQL stopped answering, reservation requests failed fast — bounded by the JDBC socket timeout, as designed —
but with `500 Internal Server Error` instead of `503 Service Unavailable` + `Retry-After`. Clients and the gateway
could not tell "retry later with the same Idempotency-Key" from "this request is broken".

## Detection
The first run of the new Toxiproxy chaos suite. `InventoryChaosIT.databasePartitionFailsFastWith503AndRecovers`
black-holes the database connection (a `timeout` toxic with no limit) and expects 503 within the socket timeout. It got
500 after 3 s.

## Root cause
- The REST error handler mapped only Spring's *transient* data-access exceptions to 503. A lost connection is
  reported as `DataAccessResourceFailureException` (SQLSTATE class 08), which is not transient.
- Worse, after the statement failed, Spring rolled back the transaction on the same dead connection. The rollback
  failed too and its `TransactionSystemException` *replaced* the original exception ("Application exception overridden
  by rollback exception"), so no handler keyed on data-access exceptions could ever see it.
- The gRPC exception handlers in the inventory and order services — the path the gateway actually uses — had the
  same gap.

## Fix
`DependencyFailures.isUnavailable` in each service's inbound adapters classifies an outage once: transient and
recoverable data-access failures, resource failures (lost connections, pool exhaustion), transactions that could not
start, commit or roll back, and transaction timeouts. REST answers 503 + `Retry-After: 2`; gRPC answers `UNAVAILABLE`,
which the gateway turns into 503 + `Retry-After`.

## Prevention
- `InventoryChaosIT` runs on every build (Testcontainers + Toxiproxy).
- `DependencyFailuresTest` pins the classification, including the rollback-failure case.

## Lessons
Exceptions on the failure path are themselves subject to failure. Fault injection found in one run what code review
had missed: the exception the handler expected was not the exception that arrived.

## Addendum — 2026-10-16: lock timeouts
Load-testing the checkout found one more exception the classification missed. When a reservation waits longer than
`lock_timeout` (2 s) for the hot inventory row, PostgreSQL cancels it with SQLSTATE `55P03`, and Spring passes it on as
an `UncategorizedSQLException` — not a transient exception — so the gRPC handler logged "Unhandled exception" and
answered `INTERNAL`. `DependencyFailures` now also inspects the SQLSTATE in the cause chain: `55P03`
(lock_not_available), `57014` (statement timeout), `53300` (too many connections), `57P01`/`57P03` (server shutting
down or starting) and class `08` (connection failures) are answered as 503 / `UNAVAILABLE`. Pinned by
`DependencyFailuresTest.aLockTimeoutOnAHotRowIsContentionNotABug`.
