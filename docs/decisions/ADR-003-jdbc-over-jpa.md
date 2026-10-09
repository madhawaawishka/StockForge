# ADR-003: Spring JDBC with explicit SQL instead of JPA
- Status: Accepted
- Date: 2026-10-08

## Context
The core of this project is controlling exactly what happens in the database under contention: row locks (`SELECT … FOR UPDATE`), conditional updates, `ON CONFLICT`, `SKIP LOCKED`, isolation levels and query plans (spec §11–§15). The spec allows JPA "where convenient" and JDBC "where understanding and controlling SQL matters".

## Options considered
1. **Spring Data JPA (Hibernate)** — less boilerplate; but SQL is generated, flushes happen implicitly, dirty checking can issue unexpected updates, and lazy loading can add queries. Concurrency behaviour becomes harder to see and explain.
2. **Spring Data JDBC** — simpler than JPA, still generates SQL for aggregates.
3. **Spring `JdbcClient` with hand-written SQL** — every statement is visible, reviewable and `EXPLAIN`-able; mapping code is written by hand.

## Decision
Use **`JdbcClient` with explicit, parameterized SQL** in persistence adapters. Mapping between rows and domain records is written by hand in each repository.

## Consequences
- Every query that runs in production is in the source code, so lock behaviour and query plans can be reasoned about and benchmarked directly.
- Race-safe idioms are first-class, e.g. `INSERT … ON CONFLICT (sku) DO NOTHING` for SKU uniqueness, which avoids both a check-then-insert race and an aborted transaction.
- More mapping code. Acceptable: aggregates are small, and the hexagonal ports keep it contained in adapters.
- The optimistic-locking strategy (W2-03) uses an explicit `version` column instead of JPA `@Version`, which shows the mechanism rather than hiding it.
