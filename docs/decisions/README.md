# Architecture Decision Records

Each ADR captures one significant decision: the context, the options considered, what was decided and its consequences.
ADRs are immutable once accepted — a changed decision gets a new ADR that supersedes the old one.

| ADR | Title | Status |
| :--- | :--- | :--- |
| [ADR-001](ADR-001-java-spring-boot-maven.md) | Java 21, Spring Boot 4 and Maven | Accepted |
| [ADR-002](ADR-002-schema-per-service.md) | One schema and one database role per service | Accepted |
| [ADR-003](ADR-003-jdbc-over-jpa.md) | Spring JDBC with explicit SQL instead of JPA | Accepted |
| [ADR-004](ADR-004-hexagonal-architecture.md) | Hexagonal architecture enforced by ArchUnit | Accepted |
| [ADR-005](ADR-005-uuidv7-identifiers.md) | Application-generated UUIDv7 identifiers | Accepted |
| [ADR-006](ADR-006-inventory-reservation-concurrency-strategy.md) | Inventory reservation concurrency control strategy | Accepted |
| [ADR-007](ADR-007-orchestrated-checkout-saga.md) | Orchestrated saga for checkout | Accepted |
| [ADR-008](ADR-008-outbox-inbox-and-event-ordering.md) | Transactional outbox, inbox and per-aggregate ordering | Accepted |
| [ADR-009](ADR-009-payment-after-reservation-expiry.md) | A payment that arrives after the reservation expired is refunded | Accepted |
| [ADR-010](ADR-010-rate-limiter-fails-open.md) | The gateway's rate limiter fails open when Redis is unavailable | Accepted |
| [ADR-011](ADR-011-gateway-authentication-and-object-authorization.md) | JWT authentication at the gateway, object-level authorization in the owning service | Accepted |
| [ADR-012](ADR-012-event-schema-evolution.md) | Event schema evolution: versioned envelope, additive changes, golden files | Accepted |
| [ADR-013](ADR-013-shared-messaging-library.md) | One messaging library for outbox, inbox and dead letters | Accepted |
| [ADR-014](ADR-014-consumer-retries-and-dead-letters.md) | Consumer failures: bounded blocking retries, then a dead-letter topic | Accepted |

## Template
```markdown
# ADR-NNN: Title
- Status: Proposed | Accepted | Superseded by ADR-XXX
- Date: YYYY-MM-DD

## Context
## Options considered
## Decision
## Consequences
## Evidence
```
