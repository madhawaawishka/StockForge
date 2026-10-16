# ADR-014: Consumer failures — bounded blocking retries, then a dead-letter topic
- Status: Accepted
- Date: 2026-10-13

## Context
A consumer that cannot process a message must neither lose it nor block its partition forever. Some failures are
transient (database restart, lock timeout), some permanent (a malformed or unknown message — a "poison pill")
(spec §22).

## Decision
- Spring Kafka `DefaultErrorHandler` with exponential backoff and jitter: 4 attempts, 500 ms initial, 5 s cap. The
  partition is paused while retrying (blocking retries), which keeps per-aggregate order.
- After the last attempt the record goes to `<topic>.DLT`, on the **same partition number**, with Spring's exception
  headers. `PoisonMessageException` (undecodable envelope, unknown type) skips the retries.
- Retries happen in **one layer only**: the consumer retries the whole message; use cases called from listeners do not
  retry internally (the REST/gRPC adapters have their own retry for serialization failures and deadlocks, which never
  runs inside a listener).
- Operators inspect dead letters (Kafka UI, `kafka-console-consumer`) and replay them with
  `POST /api/v1/admin/dead-letters/{topic}/replay` (admin-only, audited, topic whitelist). Replays are safe because
  consumers are idempotent (ADR-008).

## Consequences
- A poison message delays its partition by at most the retry budget (≈ 8 s), never indefinitely.
- Dead-lettering is counted (`stockforge_kafka_dead_lettered_total{topic}`) and alerts (`MessagesDeadLettered`).
- Non-blocking retry topics were not used: they reorder messages of one aggregate, which the saga relies on.

## Evidence
- `MessagingIT`: a poison message lands in `<topic>.DLT` with the exception class header, and the next message on the
  partition is processed.
