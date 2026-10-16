# POSTMORTEM-002: The outbox relay could never publish an event

- **Date found:** 2026-10-13
- **Severity:** Critical in production terms — no asynchronous step of checkout could ever run
- **Status:** Fixed

## Summary
Every service wrote its events to an outbox table correctly, but none of them could ever leave it. Four independent
defects each blocked publication on their own:
1. The relay's `UPDATE outbox_events SET … updated_at = …` referenced a column the table did not have, so marking an
   event as published always failed and rolled back.
2. `@EnableScheduling` was missing in the inventory and order services, so the `@Scheduled` relay never ran there.
3. The broker has `auto.create.topics.enable=false` (on purpose), but no service declared its topics, so sends would
   have blocked for `max.block.ms` and failed.
4. The relay sent synchronously inside its transaction, holding a database connection during network calls.

## Impact
Had this been deployed, reservations and orders would have been created, but payments would never have been requested,
orders never confirmed, expiries never announced and no customer ever notified. The outboxes would have grown without
bound and, without metrics, silently.

## Detection
Code review during the audit. No test had ever published through a real relay to a real broker: unit tests used
in-memory fakes and the integration tests could not start (POSTMORTEM-001).

## Root cause
The outbox was implemented four times, once per service, each copy slightly different, each tested only through
fakes. The contract between the relay's SQL and the table's DDL lived in two files that no test exercised together.

## Fix
- One implementation in `libs/messaging` (ADR-013) used by every service: relay with an advisory lock per schema,
  `SKIP LOCKED`, ordered by `seq`, asynchronous sends, and `PUBLISHED` marked only for the acknowledged prefix
  (ADR-008).
- Services declare their topics at start-up (`stockforge.messaging.topics.declare`), with dead-letter topics.
- Scheduling runs on a lifecycle-managed thread inside the library, so it cannot be forgotten.
- Metrics and alerts: `stockforge_outbox_pending_events`, `stockforge_outbox_oldest_pending_age_seconds`
  (`OutboxEventsStuck` at > 30 s), `stockforge_outbox_publish_lag_seconds` (NFR-7).

## Prevention
- `MessagingIT` runs the relay against real PostgreSQL and Kafka containers, including a broker outage.
- `ReliableMessagingIT` asserts that a reservation's event reaches Kafka and its outbox row becomes `PUBLISHED`.
- `InventoryChaosIT` pauses Kafka and verifies the backlog drains after recovery.

## Lessons
A pattern whose whole value is reliability must be tested against the real infrastructure it protects against. Fakes
proved that the code *called* the outbox, not that anything was ever delivered.
