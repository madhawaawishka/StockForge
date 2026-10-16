# ADR-008: Transactional outbox, inbox and per-aggregate ordering
- Status: Accepted
- Date: 2026-10-13

## Context
A service that writes to its database and then publishes to Kafka can lose the event (crash between the two) or
publish an event for a change that rolled back. Consumers receive messages at least once, so they must tolerate
duplicates. Some consumers also need order: `ReservationCreated` before `ReservationConfirmed` for one reservation
(spec §18, §20).

## Decision
- **Outbox.** The business transaction inserts the full event envelope into `outbox_events` (JSONB, plus W3C trace
  headers). `OutboxWriter` refuses to run outside a transaction.
- **Relay.** One relay per service schema at a time, enforced with `pg_try_advisory_xact_lock`, reads pending rows in
  `seq` order with `FOR UPDATE SKIP LOCKED`, sends them asynchronously with `acks=all` and an idempotent producer, and
  marks as `PUBLISHED` only the prefix of rows the broker acknowledged. A failed send stops the batch, so a later
  event of the same aggregate is never published before an earlier one.
- **Partition key = aggregate id** (reservation id, order id, payment id). All events of one aggregate land on one
  partition and are consumed in order; different aggregates proceed in parallel.
- **Inbox.** Consumers insert `(consumer_group, event_id)` into `processed_events` in the same transaction as their
  business change; a duplicate key means "already done" and the message is acknowledged without effect. The event id
  travels both in the envelope and in the `stockforge-event-id` header.
- **Retention.** Published outbox rows are deleted after 7 days, inbox rows after 14 days — longer than any replay
  window, so a replayed duplicate is still recognised.

## Consequences
- At-least-once delivery + idempotent consumers = exactly-once effect, without Kafka transactions spanning the
  database.
- Events are delayed by the relay's poll interval (200 ms) plus the broker round trip; NFR-7 (P95 < 1 s) is measured
  by `stockforge_outbox_publish_lag_seconds`.
- A Kafka outage does not fail business requests: events wait in PostgreSQL and drain afterwards
  (`InventoryChaosIT.kafkaOutageIsBufferedByTheOutboxAndDrainedAfterRecovery`). The backlog is visible
  (`stockforge_outbox_oldest_pending_age_seconds`, alert `OutboxEventsStuck`).
- Ordering is per aggregate only. No consumer may assume an order across aggregates.

## Evidence
- `MessagingIT`: write order preserved, events kept while the broker is down, poison messages dead-lettered,
  duplicate deliveries applied once, retention.
- `ReliableMessagingIT`: a duplicated `ConfirmReservation` sells the units once.
