# ADR-012: Event schema evolution — versioned envelope, additive changes, golden-file contracts
- Status: Accepted
- Date: 2026-10-13

## Context
Events outlive the code that produced them: they wait in outboxes, in Kafka, in dead-letter topics, and are replayed
days later. Producers and consumers deploy independently, so for a while old and new versions of each run side by
side (spec §18).

## Decision
- Every message is an `EventEnvelope`: `eventId`, `eventType`, `schemaVersion`, `aggregateType`, `aggregateId`,
  `occurredAt`, `correlationId`, `causationId`, `payload`. The envelope itself only ever gains optional fields.
- Payloads are Java records in `libs/event-schemas`, the only code shared between services. Amounts are strings,
  timestamps ISO-8601 UTC, ids UUID strings.
- **Compatible changes only** within a schema version: add optional fields; never rename, remove or change the type
  or meaning of a field. Consumers ignore unknown fields.
- A breaking change gets a new `eventType` or a new `schemaVersion`, published *alongside* the old one until every
  consumer has moved; consumers dispatch on `(eventType, schemaVersion)`.
- **Golden-file contract tests** (`MessageContractTest`) serialize every payload and compare it with a checked-in JSON
  file, and deserialize the file back. Changing a contract means changing a reviewed file, on purpose.
- gRPC contracts follow the same rule and are checked by `buf lint` and `buf breaking` against `main` in CI.

## Consequences
- A consumer can be deployed before or after its producer.
- Messages in a dead-letter topic remain replayable after deployments.
- No schema registry is needed at this scale; the contract tests and code review play its role. With many teams, a
  registry with compatibility checks (e.g. Apicurio, Confluent) would replace the golden files.

## Evidence
- `MessageContractTest`: 56 tests over every payload, in both directions.
