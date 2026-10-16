# ADR-013: One messaging library for outbox, inbox and dead letters
- Status: Accepted
- Date: 2026-10-13

## Context
Four services need the same reliable-messaging machinery: outbox writer and relay, inbox, retries with dead-letter
topics, retention, topic declaration and metrics. The audit before Week 6 found four hand-copied versions of it with
four different bugs: a relay updating a column that did not exist, consumers deduplicating by Kafka key instead of
event id, an inbox insert outside the business transaction, and scheduling never enabled. The project rule is that
services share no code except contracts.

## Options considered
1. Keep per-service copies and fix each.
2. A shared **infrastructure** library with no domain knowledge.
3. A framework such as Debezium for the outbox (CDC from the WAL) or Spring Modulith's event publication registry.

## Decision
Option 2: `libs/messaging`, a Spring Boot auto-configuration (`stockforge.messaging.*`) that services opt into with
their own schema. It contains no business types — only envelopes, SQL against its own two tables, and Kafka plumbing.
The no-shared-domain-code rule stands; shared *infrastructure* is allowed when it is generic and versioned with the
services.

Debezium was not chosen: it adds Kafka Connect and replication-slot operations to a single-developer project, while
polling at 200 ms already meets NFR-7.

## Consequences
- One implementation to test hard (`MessagingIT` with real PostgreSQL and Kafka) instead of four to test lightly.
- Services cannot customise the outbox table freely; its DDL is a reference script copied into each service's Flyway
  migration, so each service still owns its schema.
- A breaking change to the library touches every service at once; acceptable while they live in one repository.
