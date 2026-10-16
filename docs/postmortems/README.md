# Postmortems

Blameless write-ups of real defects found in this project (spec §36): what happened, why it was possible, how it was
found and what now prevents it. Each one names the test or guard that would catch it today.

Earlier versions of this folder contained two narrative postmortems with invented incidents and numbers. They were
removed on 2026-10-13: a postmortem is only useful if it happened.

| # | Title | Found by | Guard today |
| :--- | :--- | :--- | :--- |
| [001](POSTMORTEM-001-ci-never-ran-the-build.md) | CI never ran the build, so a service that could not start was reported as done | audit: CI annotations, `./mvnw verify` | executable `mvnw`, session protocol, CI on every PR |
| [002](POSTMORTEM-002-outbox-could-never-publish.md) | The outbox relay could never publish an event | audit (code review) | `MessagingIT`, `ReliableMessagingIT`, `InventoryChaosIT` |
| [003](POSTMORTEM-003-rate-limiter-failed-open-on-redis-handshake.md) | The rate limiter failed open on every request | `GatewayApiIT` | 100 ms timeout, fail-open metric + alert |
| [004](POSTMORTEM-004-refunds-discarded-as-duplicates.md) | Refund commands were discarded as duplicates | audit (code review) | inbox keyed by event id, `PaymentIT` |
| [005](POSTMORTEM-005-expiry-leaked-purchase-limits.md) | Expired reservations kept the shopper's purchase allowance | audit (code review) | `ReservationLifecycleTest`, `ReliableMessagingIT`, invariant script |
| [006](POSTMORTEM-006-database-partition-answered-500.md) | A database partition (and, later, a lock timeout) was answered with 500 instead of 503 | `InventoryChaosIT` (Toxiproxy), checkout load test | `DependencyFailures`, chaos suite |
| [007](POSTMORTEM-007-first-dead-letter-never-alerted.md) | The first dead-lettered message would never have raised an alert | README verification on a running stack | counters registered at start-up, `DeadLetterErrorHandlingTest`, `MicrometerSagaObserverTest` |
