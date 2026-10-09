# ADR-002: One schema and one database role per service
- Status: Accepted
- Date: 2026-10-08

## Context
StockForge has three services (inventory, order, payment). If they share tables, any service can read or write any other service's data: schemas and deployments become coupled, and the real distributed-consistency problem of checkout is hidden behind cross-service joins (spec §7).

Running a separate PostgreSQL server per service is the cleanest isolation but triples local resource use and cost.

## Options considered
1. **Shared database, shared tables** — simplest, but an anti-pattern for services: no ownership, hidden coupling.
2. **One PostgreSQL server per service** — strongest isolation; most resources and operational overhead.
3. **One PostgreSQL instance, one schema and one login role per service** — ownership enforced by database privileges, with the cost of a single instance.

## Decision
Option 3. Each service:
- connects with its own role (`inventory_svc`, `order_svc`, `payment_svc`) that owns only its schema (`inventory`, `ordering`, `payment`);
- sets its connection schema explicitly (HikariCP `schema`) and runs its own Flyway migrations in that schema;
- has no privileges on other schemas, so a cross-service query fails instead of silently coupling services.

Default access to the database and the `public` schema is revoked from `PUBLIC`. The local roles and schemas are created by `database/init/01-roles-and-schemas.sql`.

## Consequences
- Data ownership is enforced by the database, not by code review discipline.
- Cross-service data must travel through APIs or events, which makes the saga and outbox work (Weeks 3–4) necessary and visible.
- One instance is a shared failure and capacity domain; acceptable for a portfolio system, and moving a schema to its own server later needs no application change beyond the connection URL.
- Migrations live with their service (`src/main/resources/db/migration`) rather than in a shared `database/migrations` folder, so a service's schema and code are versioned and deployed together.
