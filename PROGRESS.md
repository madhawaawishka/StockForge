# StockForge — Build Progress Tracker

This file is the single source of truth for **where the build is**. Every working session starts by reading it and ends by updating it.

- **Specification:** [high_concurrency_inventory_reservation_project_specification.md](high_concurrency_inventory_reservation_project_specification.md) (section numbers below refer to it)
- **Working conventions:** [CLAUDE.md](CLAUDE.md)

---

## How to resume a session
1. Read **Current status** and **Next steps** below.
2. `git status` and `git branch --show-current` — confirm you are on the branch listed in Current status.
3. Start Docker Desktop (integration tests and the local stack need it).
4. Run `./mvnw verify` to confirm the build is green before changing anything.
5. Pick the first unchecked task in **Next steps**.

## How to end a session
1. Run `./mvnw verify` — record the result honestly in the session log (pass/fail, what was skipped).
2. Tick finished tasks in the **Milestone checklist**.
3. Add a **Session log** entry: what was done, decisions, problems, verification.
4. Rewrite **Current status** and **Next steps** so the next session can start cold.
5. Commit (Conventional Commits).

---

## Current status
| Item | Value |
| :--- | :--- |
| Phase | Week 1 — Walking skeleton (Tier 1) |
| Active branch | `feat/week1-walking-skeleton` |
| Build status | `./mvnw test` green (67 unit tests). Integration tests not written yet. |
| Last updated | 2026-10-09 |

## Next steps
1. W1-09 Testcontainers integration tests: API end to end, DB constraints, concurrent duplicate SKU, operational endpoints.
2. W1-03 `docker-compose.yml` + `database/init` script; run the service with the `local` profile against it.
3. W1-10 CI workflow, Dependabot, PR template.
4. W1-11 README v0, ADR-001…005, `docs/api/problems.md`.
5. Full `./mvnw verify`, then commit and tick W1 tasks.

---

## Milestone checklist
Task IDs are stable — reference them in commits and session logs.

### Week 1 — Walking skeleton (Tier 1)
- [x] W1-01 Repository hygiene: `.gitignore`, `.gitattributes` (LF endings), `.editorconfig`
- [x] W1-02 Maven multi-module parent + Maven Wrapper + Spotless (palantir-java-format) + JaCoCo + Failsafe
- [ ] W1-03 Docker Compose: PostgreSQL 18 (one schema + role per service, `pg_stat_statements`), Redis 8, Kafka 4 (KRaft)
- [ ] W1-04 Inventory service skeleton: hexagonal packages, typed config, Actuator probes, structured logging, DB timeouts
- [ ] W1-05 Flyway V1: `products` + `inventory` with constraints (stock-conservation CHECK)
- [x] W1-06 Domain model: `ProductId` (UUIDv7), `Sku`, `Money`, `Product`, `Inventory` + unit tests
- [ ] W1-07 Use cases: create product with initial stock, get product, list products (keyset pagination), get availability
- [ ] W1-08 REST adapter: `/api/v1/admin/products`, `/api/v1/products…`, RFC 9457 Problem Details, validation, OpenAPI
- [ ] W1-09 Tests: domain unit, use-case unit (in-memory fakes), WebMvc slice, Testcontainers ITs, ArchUnit, DB-constraint IT
- [ ] W1-10 CI: GitHub Actions (gitleaks, Spotless, unit + IT, JaCoCo, compose validation), Dependabot, PR template
- [ ] W1-11 Docs: README v0, first ADRs, `docs/api/problems.md`

### Week 2 — Concurrency core (Tier 1)
- [ ] W2-01 `Reservation` aggregate + state machine (PENDING → CONFIRMED / CANCELLED / EXPIRED) + tests (§16)
- [ ] W2-02 Flyway V2: `reservations`, partial indexes, `idempotency_keys` (§13, §19)
- [ ] W2-03 `StockReservationStrategy`: naive (oversell demo, test-only), pessimistic, optimistic, atomic conditional update (§14)
- [ ] W2-04 Shared concurrency test suite for all strategies (latch start, repeated, invariant assertions) (§29)
- [ ] W2-05 Reservation API: `POST /api/v1/reservations` (Idempotency-Key), `GET`, `POST …/cancel` (§12, §19)
- [ ] W2-06 Purchase limit + write-skew reproduction test + fix (§15)
- [ ] W2-07 Retryable SQLSTATE handling (`40001`, `40P01`), lock/statement timeouts verified (§14 F)
- [ ] W2-08 ADR: concurrency strategy; benchmark-notes skeleton in `docs/benchmarks/`

### Week 3 — Orders, gateway, gRPC, DB & cache performance (Tier 1/2)
- [ ] W3-01 `libs/proto` + buf lint/breaking; Spring gRPC server/client with deadlines (§12, §23)
- [ ] W3-02 API gateway (REST → gRPC), JWT auth (Spring Security resource server), object-level authorization (§28)
- [ ] W3-03 Order service: `orders`, `order_items`, `order_status_history`, state machine, idempotent create (§16, §19)
- [ ] W3-04 Large-dataset seed generator; EXPLAIN ANALYZE index report (§13)
- [ ] W3-05 Hikari pool sizing + pool metrics (§24)
- [ ] W3-06 Redis cache-aside for products + stampede protection + after-commit invalidation (§17)
- [ ] W3-07 Redis token-bucket rate limiting at the gateway (§23 C)

### Week 4 — Reliable asynchronous workflow (Tier 1)
- [ ] W4-01 Outbox table + relay (`SKIP LOCKED`) per service; event envelope + `libs/event-schemas` (§18, §20)
- [ ] W4-02 Inbox (`processed_events`) idempotent consumers (§18)
- [ ] W4-03 Payment service with controllable simulated outcomes (FR-6)
- [ ] W4-04 Orchestrated checkout saga + compensations + saga timeout (§16)
- [ ] W4-05 Reservation expiry worker + reconciliation job (§21)
- [ ] W4-06 Retries with backoff, DLT, replay endpoint (§22)
- [ ] W4-07 Payment-after-expiry handling + test (§16 C)
- [ ] W4-08 Notification worker (§10)

### Week 5 — Observability, performance, resilience (Tier 1/2)
- [ ] W5-01 OpenTelemetry + Prometheus/Grafana/Tempo/Loki; trace context through the outbox (§25)
- [ ] W5-02 Dashboards, alert rules, SLOs (§25)
- [ ] W5-03 k6 scenarios (smoke, load, contention, spike, soak) + invariant-check script (§26)
- [ ] W5-04 Benchmarks: strategies comparison, platform vs virtual threads → `docs/benchmarks/` (§14, §24)
- [ ] W5-05 Resilience4j timeouts, circuit breakers, bulkheads (§23)
- [ ] W5-06 Toxiproxy failure-injection tests (§27)
- [ ] W5-07 Postmortem(s) for real bugs found (§36)

### Week 6 — Hardening and presentation (Tier 1/2, some Tier 3)
- [ ] W6-01 Security pass: OWASP API Top 10 review, authorization tests, Trivy/dependency scanning (§28)
- [ ] W6-02 Dockerfiles (multi-stage, non-root), full compose stack, graceful-shutdown test (§33)
- [ ] W6-03 Terraform + optional AWS deployment (Tier 3) (§34)
- [ ] W6-04 Final README with results, C4 + sequence diagrams, demo GIF (§36, §39)
- [ ] W6-05 Resume bullets + interview prep notes (§40, §41)

---

## Decisions log
Short pointers; full reasoning lives in `docs/decisions/`.

| Date | Decision | Record |
| :--- | :--- | :--- |

## Deviations from the specification
| Spec says | What we do | Why |
| :--- | :--- | :--- |

## Open questions for the project owner
- [ ] Which LICENSE should the repository use (e.g., MIT)? Not added until decided.

## Known issues / tech debt
_None yet._

---

## Session log
_Newest first._

### Session 2 — 2026-10-09 (in progress)
- Resumed from session 1. Re-ran `./mvnw test` after the two test fixes: **67/67 unit tests pass**.

### Session 1 — 2026-10-08 (interrupted before commit)
**Done**
- Reviewed and extended the specification to v1.1 (committed by the owner as `c75896c`).
- Created this tracker, `CLAUDE.md` (working agreement) and branch `feat/week1-walking-skeleton`.
- W1-01 repo hygiene files; W1-02 Maven parent (Spring Boot 4.1.1, Java 21 release target), Maven Wrapper 3.9.14, Spotless + palantir-java-format, JaCoCo, `-Xlint:all` with warnings as errors.
- Inventory service code (hexagonal): domain model, ports, `CreateProductUseCase`, `ProductCatalogQueries`, JDBC repositories (`ON CONFLICT (sku) DO NOTHING`), UUIDv7 generator, REST controller, RFC 9457 error handling, Flyway V1 with stock-conservation CHECK, `application.yml` (env-var config, DB timeouts, probes, ECS JSON logs) and `application-local.yml`.
- Unit tests: domain, use cases with in-memory fakes, WebMvc slice (12 HTTP-contract tests), ArchUnit rules.

**Problems found and fixed**
- ArchUnit onion rule flagged the `config` package (composition root) for referencing every layer → explicitly ignored as the composition root.
- `SkuTest` used a hand-typed 64-character SKU as "too long" (64 is the valid maximum) → replaced with explicit boundary tests (`"A".repeat(64)` accepted, 65 rejected).

**Environment notes**
- Local JDK is 23; the build targets Java 21 (`--release 21`). CI will use Temurin 21.
- Docker Desktop must be started manually; `make` and `k6` are not installed (k6 will run via the `grafana/k6` image).
- Spring Boot 4 moved test annotations: `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`, `@JdbcTest` → `org.springframework.boot.jdbc.test.autoconfigure`; Testcontainers 2 uses `org.testcontainers.postgresql.PostgreSQLContainer`.
