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
5. Hand over: the project owner commits and pushes. List the uncommitted changes with suggested Conventional Commit messages.

---

## Current status
| Item | Value |
| :--- | :--- |
| Phase | **Week 1 complete** — next: Week 2, concurrency core |
| Active branch | `feat/week1-walking-skeleton` (owner to commit, push and merge to `main`) |
| Build status | `./mvnw clean verify` green with Error Prone: 67 unit + 21 integration tests; coverage 98.7% lines / 89.6% branches |
| CI on GitHub | Not run yet — confirm the first workflow run is green after pushing |
| Last updated | 2026-10-09 |

## Next steps
1. Owner: commit the session 2 changes, push, open a PR to `main`, confirm CI passes, merge.
2. Create branch `feat/week2-concurrency-core` from the updated `main`.
3. W2-01 `Reservation` aggregate + state machine with unit tests.
4. W2-02 Flyway V2 (`reservations`, partial indexes, `idempotency_keys`).
5. W2-03/W2-04 the four `StockReservationStrategy` implementations behind one interface, sharing one concurrency test suite — start with the naive strategy and a test that **proves it oversells**.

---

## Milestone checklist
Task IDs are stable — reference them in commits and session logs.

### Week 1 — Walking skeleton (Tier 1)
- [x] W1-01 Repository hygiene: `.gitignore`, `.gitattributes` (LF endings), `.editorconfig`
- [x] W1-02 Maven multi-module parent + Maven Wrapper + Spotless (palantir-java-format) + JaCoCo + Failsafe
- [x] W1-03 Docker Compose: PostgreSQL 18 (one schema + role per service, `pg_stat_statements`), Redis 8, Kafka 4 (KRaft)
- [x] W1-04 Inventory service skeleton: hexagonal packages, typed config, Actuator probes, structured logging, DB timeouts
- [x] W1-05 Flyway V1: `products` + `inventory` with constraints (stock-conservation CHECK)
- [x] W1-06 Domain model: `ProductId` (UUIDv7), `Sku`, `Money`, `Product`, `Inventory` + unit tests
- [x] W1-07 Use cases: create product with initial stock, get product, list products (keyset pagination), get availability
- [x] W1-08 REST adapter: `/api/v1/admin/products`, `/api/v1/products…`, RFC 9457 Problem Details, validation, OpenAPI
- [x] W1-09 Tests: domain unit, use-case unit (in-memory fakes), WebMvc slice, Testcontainers ITs, ArchUnit, DB-constraint IT
- [x] W1-10 CI: GitHub Actions (gitleaks, Spotless, unit + IT, JaCoCo, compose validation), Error Prone, Dependabot, PR template
- [x] W1-11 Docs: README v0, first ADRs, `docs/api/problems.md`

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
| 2026-10-08 | Java 21 target (local JDK 23), Spring Boot 4.1, Maven + wrapper | [ADR-001](docs/decisions/ADR-001-java-spring-boot-maven.md) |
| 2026-10-08 | One schema + one DB role per service on a shared PostgreSQL instance | [ADR-002](docs/decisions/ADR-002-schema-per-service.md) |
| 2026-10-08 | Spring `JdbcClient` with explicit SQL instead of JPA | [ADR-003](docs/decisions/ADR-003-jdbc-over-jpa.md) |
| 2026-10-08 | Hexagonal architecture enforced by ArchUnit; use cases wired as `@Bean` | [ADR-004](docs/decisions/ADR-004-hexagonal-architecture.md) |
| 2026-10-08 | Application-generated UUIDv7 identifiers | [ADR-005](docs/decisions/ADR-005-uuidv7-identifiers.md) |
| 2026-10-09 | Error Prone runs inside `javac` on every build; its findings fail the build | `pom.xml`, `.mvn/jvm.config` |

## Deviations from the specification
| Spec says | What we do | Why |
| :--- | :--- | :--- |
| §37 Week 1 output: "one request flows through the gateway to Inventory" | Requests reach the inventory service directly; the gateway arrives in W3-02 together with gRPC and JWT | A REST pass-through gateway built now would be thrown away in Week 3 |
| §31 migrations in `database/migrations/` | Each service owns its Flyway migrations in `src/main/resources/db/migration`; `database/` holds only local init scripts | Schema and code are versioned and deployed together (ADR-002) |
| §8 "Spring Data JPA + Spring JDBC" | Spring JDBC (`JdbcClient`) only | Full control and visibility of SQL for the concurrency work (ADR-003) |
| §31/§32 `Makefile` | Not added; commands are documented in README and CLAUDE.md | `make` is not installed on the owner's Windows machine; revisit if a cross-platform task runner adds value |
| §12 API table (no product-creation endpoint listed) | `POST /api/v1/admin/products` | Admin prefix gives Week 3 security a single path rule to protect |

## Open questions for the project owner
- [ ] Which LICENSE should the repository use (e.g., MIT)? Not added until decided.
- [ ] Install JDK 25 LTS and move the target from Java 21 to 25 (enables JEP 491 for the virtual-thread benchmark)? See ADR-001.

## Known issues / tech debt
- Admin endpoints are unauthenticated until W3-02 (JWT). Do not expose the service publicly before then.
- Problem Details responses do not yet carry a `traceId` (added with tracing in W5-01).
- Redis and Kafka run in Docker Compose but no code uses them yet (W3-06, W4-01).
- JaCoCo reports coverage but enforces no threshold — deliberate (spec §29: chase meaningful tests, not a percentage).

---

## Session log
_Newest first._

### Session 2 — 2026-10-09 — Week 1 completed
**Done**
- Resumed from session 1. Re-ran `./mvnw test` after the two test fixes: 67/67 unit tests pass.
- Committed session 1 work as four Conventional Commits (`3803525`…`41d497b`).
- W1-09 integration tests (`7a5b094`): `ProductApiIT`, `DatabaseConstraintsIT`, `CreateProductConcurrencyIT` (16 simultaneous creates of one SKU → exactly one product), `OperationalReadinessIT` (probes, Prometheus, build info, OpenAPI, schema + server-side timeouts). All share one context/container via `@InventoryIntegrationTest`.
- W1-03 `docker-compose.yml` (PostgreSQL 18.6 + `pg_stat_statements`, Redis 8.8.3 without persistence, Kafka 4.3.1 single-node KRaft with auto topic creation off) and `database/init/01-roles-and-schemas.sql`.
- W1-10 `.github/workflows/ci.yml` (gitleaks job + build job: compose validation, `./mvnw verify`, report upload; least-privilege permissions, concurrency cancel), `dependabot.yml` (Maven, Actions, Compose), PR template. Added **Error Prone 2.50.0** to every compile (`.mvn/jvm.config` holds its JDK flags).
- W1-11 README v0 (status, quick start, configuration table, testing), ADR-001…005 + index, `docs/api/problems.md`.
- API docs/Swagger UI made opt-in (`INVENTORY_API_DOCS_ENABLED`, default `false`); enabled by the `local` profile and in tests.
- Owner asked to commit and push themselves from now on → `CLAUDE.md` and "How to end a session" updated; session 2 changes after `7a5b094` are left uncommitted for the owner.

**Problems found and fixed**
- Error Prone's first run flagged `OffsetDateTime.now()` in `DatabaseConstraintsIT` (implicit default time zone) → now `OffsetDateTime.now(ZoneOffset.UTC)`. Main code had no findings.
- SpringDoc warned that API docs were publicly enabled by default → made opt-in (see above).
- Stopping `mvnw spring-boot:run` does not stop the forked application JVM on Windows; the process on port 8081 had to be stopped separately.
- In Git Bash, `docker compose exec … /opt/...` paths get rewritten to Windows paths → use `MSYS_NO_PATHCONV=1`.

**Verification**
- `./mvnw clean verify` → BUILD SUCCESS: 67 unit + 21 integration tests, Error Prone clean, Spotless clean, JaCoCo 98.7% lines / 89.6% branches.
- gitleaks v8.30.0: no leaks in the working tree or in the 7-commit history.
- `docker compose up --wait`: all three containers healthy. `inventory_svc` can create tables in `inventory` but gets `permission denied` on `ordering` and `public`. Kafka topic create/list/delete works on both the host listener (`localhost:9092`) and the container listener (`kafka:19092`).
- Service run with the `local` profile: Flyway applied V1 as `inventory_svc`, started in ~3 s, plain-text logs. curl smoke test: create → 201 + `Location`, get/list/availability → 200 with identical microsecond timestamps, duplicate SKU → 409 `application/problem+json`, Swagger UI 200, Hikari metrics exposed.
- Service run with **no profile**, configured only via environment variables: API docs and Swagger UI → 404, API → 200, logs in ECS JSON.
- **Not verified:** the GitHub Actions workflow itself (needs a push).
- Docker Compose stack left running; stop with `docker compose down` (add `-v` to wipe the database volume).

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
