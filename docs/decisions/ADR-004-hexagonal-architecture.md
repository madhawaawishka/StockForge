# ADR-004: Hexagonal architecture enforced by ArchUnit
- Status: Accepted
- Date: 2026-10-08

## Context
Business rules (stock conservation, reservation state transitions, purchase limits) must be testable in milliseconds and must not depend on HTTP, SQL or Kafka details. Architecture rules that live only in a document erode silently (spec §9).

## Options considered
1. **Layered packages by technical type** (`controller`, `service`, `repository`) — familiar, but nothing stops business logic leaking into controllers or SQL into services.
2. **Hexagonal (ports and adapters)** — domain at the centre, adapters at the edges, dependencies pointing inwards.
3. **Hexagonal, with Spring stereotypes on use cases** — less wiring code, but the application layer depends on Spring's component model.

## Decision
Hexagonal architecture inside each service, with these packages under `com.stockforge.<service>`:

| Package | Contains | May depend on |
| :--- | :--- | :--- |
| `domain.model` | Aggregates, value objects | Java only |
| `domain.port` | Repository/publisher interfaces | Java, `domain.model` |
| `application` | Use cases, transaction boundaries | Java, domain, `org.springframework.transaction.annotation` |
| `adapter.in.*` / `adapter.out.*` | Web, persistence, messaging, id generation | Anything; adapters do not depend on each other |
| `config` | Spring wiring (the composition root) | Everything |

Use cases are registered as `@Bean`s in `config` instead of being annotated with `@Service`. The application layer may use `@Transactional`: transaction boundaries are a use-case concern, and the annotation carries no other framework coupling.

The rules are executable: `HexagonalArchitectureTest` (ArchUnit) fails the build when a dependency points the wrong way.

## Consequences
- Domain and use-case tests run without Spring or a database (in-memory fakes implement the ports).
- Swapping an adapter (e.g., REST → gRPC in Week 3) touches only adapter and config code.
- A little more wiring code in `config`.
- Allowing `@Transactional` in the application layer is a deliberate, documented exception; any further Spring dependency there needs a new ADR.
