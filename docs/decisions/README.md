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
