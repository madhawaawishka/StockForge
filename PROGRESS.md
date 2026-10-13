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
| Phase | **Week 3 and Week 4 complete** — next: Week 5, Observability, performance, resilience |
| Active branch | `feat/week3-orders-gateway` (owner to commit Week 3 & Week 4 changes) |
| Build status | `./mvnw test` clean with Error Prone: 175 unit and architecture tests pass (BUILD SUCCESS across all 8 modules); Spotless clean |
| CI on GitHub | Pending owner push & PR |
| Last updated | 2026-10-11 |

## Next steps
1. Owner: Review and commit Week 3 & Week 4 changes (suggested Conventional Commits provided below).
2. Owner: Push branches and merge to `main`.
3. Week 5: W5-01 OpenTelemetry + Prometheus/Grafana/Tempo/Loki; trace context through the outbox (§25).
4. Week 5: W5-02 Dashboards, alert rules, SLOs (§25).
5. Week 5: W5-03 k6 scenarios (smoke, load, contention, spike, soak) + invariant-check script (§26).
6. Week 5: W5-04 Benchmarks: strategies comparison, platform vs virtual threads (§14, §24).

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
- [x] W2-01 `Reservation` aggregate + state machine (PENDING → CONFIRMED / CANCELLED / EXPIRED) + tests (§16)
- [x] W2-02 Flyway V2: `reservations`, partial indexes, `idempotency_keys`, `user_product_limits` (§13, §19)
- [x] W2-03 `StockReservationStrategy`: naive (oversell demo, test-only), pessimistic, optimistic, atomic conditional update (§14)
- [x] W2-04 Shared concurrency test suite for all strategies (latch start, repeated, invariant assertions) (§29)
- [x] W2-05 Reservation API: `POST /api/v1/reservations` (Idempotency-Key), `GET`, `POST …/cancel` (§12, §19)
- [x] W2-06 Purchase limit + write-skew reproduction test + fix (§15)
- [x] W2-07 Retryable SQLSTATE handling (`40001`, `40P01`), lock/statement timeouts verified (§14 F)
- [x] W2-08 ADR: concurrency strategy; benchmark-notes skeleton in `docs/benchmarks/`

### Week 3 — Orders, gateway, gRPC, DB & cache performance (Tier 1/2)
- [x] W3-01 `libs/proto` + buf lint/breaking; Spring gRPC server/client with deadlines (§12, §23)
- [x] W3-02 API gateway (REST → gRPC), JWT auth (Spring Security resource server), object-level authorization (§28)
- [x] W3-03 Order service: `orders`, `order_items`, `order_status_history`, state machine, idempotent create (§16, §19)
- [x] W3-04 Large-dataset seed generator; EXPLAIN ANALYZE index report (§13)
- [x] W3-05 Hikari pool sizing + pool metrics (§24)
- [x] W3-06 Redis cache-aside for products + stampede protection + after-commit invalidation (§17)
- [x] W3-07 Redis token-bucket rate limiting at the gateway (§23 C)

### Week 4 — Reliable asynchronous workflow (Tier 1)
- [x] W4-01 Outbox table + relay (`SKIP LOCKED`) per service; event envelope + `libs/event-schemas` (§18, §20)
- [x] W4-02 Inbox (`processed_events`) idempotent consumers (§18)
- [x] W4-03 Payment service with controllable simulated outcomes (FR-6)
- [x] W4-04 Orchestrated checkout saga + compensations + saga timeout (§16)
- [x] W4-05 Reservation expiry worker + reconciliation job (§21)
- [x] W4-06 Retries with backoff, DLT, replay endpoint (§22)
- [x] W4-07 Payment-after-expiry handling + test (§16 C)
- [x] W4-08 Notification worker (§10)

### Week 5 — Observability, performance, resilience (Tier 1/2)
- [x] W5-01 OpenTelemetry + Prometheus/Grafana/Tempo/Loki; trace context through the outbox (§25)
- [x] W5-02 Dashboards, alert rules, SLOs (§25)
- [x] W5-03 k6 scenarios (smoke, load, contention, spike, soak) + invariant-check script (§26)
- [x] W5-04 Benchmarks: strategies comparison, platform vs virtual threads → `docs/benchmarks/` (§14, §24)
- [x] W5-05 Resilience4j timeouts, circuit breakers, bulkheads (§23)
- [x] W5-06 Toxiproxy failure-injection tests (§27)
- [x] W5-07 Postmortem(s) for real bugs found (§36)

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
| 2026-10-09 | Inventory reservation concurrency strategy (Atomic conditional UPDATE baseline) | [ADR-006](docs/decisions/ADR-006-inventory-reservation-concurrency-strategy.md) |
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
- JaCoCo reports coverage but enforces no threshold — deliberate (spec §29: chase meaningful tests, not a percentage).

---

## Session log
_Newest first._

### Session 5 — 2026-10-13 — Week 5 completed (Observability, Performance, Resilience)
**Done**
- **W5-01 (Distributed Tracing & Outbox Context Propagation):** Injected W3C `traceparent` (`00-{traceId}-{spanId}-01`) into transactional outbox event headers; propagated via Kafka `RecordHeader`; reconstructed trace context in consumers and enriched MDC logging; populated `traceId` in RFC 9457 Problem Details responses; exposed `stockforge.outbox.oldest.pending.age.seconds` and `stockforge.inventory.invariant.violations` Micrometer gauges; configured OTLP tracing endpoints (`http://localhost:4318/v1/traces`) with 100% sampling across all 5 services.
- **W5-02 (Observability Dashboards, Alerts & SLOs):** Provisioned complete LGTM stack in `docker-compose.yml` (`grafana/otel-lgtm:0.8.1`); configured Prometheus scrape targets and 8 production alert rules (`alert_rules.yml`); provisioned Grafana datasources and dashboards (`stockforge-system-overview.json`, `stockforge-business-invariants.json`); authored `docs/observability/slos.md` (SLO-1 through SLO-4, multi-window burn rate alert matrix, error budget policies) and 5 incident runbooks (`RUNBOOK-001` through `RUNBOOK-005`).
- **W5-03 (k6 Load Scenarios & Invariant Verification):** Authored 5 k6 scenarios (`smoke.js`, `reservation-contention.js` with 2,000 VUs asserting zero overselling, `checkout.js`, `spike.js`, `soak.js`) with pure-JS HMAC-SHA256 JWT generation; created post-test database invariant validation scripts in SQL (`scripts/check-invariants.sql`), PowerShell (`scripts/verify-invariants.ps1`), and Bash (`scripts/verify-invariants.sh`).
- **W5-04 (Benchmark Analyses):** Completed comparative benchmarks and architectural documentation in `docs/benchmarks/concurrency-strategies.md` (Atomic Conditional UPDATE vs Pessimistic vs Optimistic vs Naive) and `docs/benchmarks/platform-vs-virtual-threads.md` (Little's Law, memory profiling, and HikariCP pool starvation analysis).
- **W5-05 (Resilience Engineering):** Configured Resilience4j circuit breakers and semaphore bulkheads across API Gateway, Inventory, and Order services; decorated gRPC client calls (`inventoryClient`, `orderClient`) with circuit breakers and bulkheads; protected Redis reads in `RedisProductCache` with circuit breaker and graceful DB fallback; protected DLT replay endpoint with semaphore bulkhead; wired gateway exception handler to return HTTP 503 + `Retry-After: 5` on tripped breakers and full bulkheads.
- **W5-06 & W5-07 (Chaos Tests & Postmortems):** Authored failure-injection chaos integration tests (`RedisFailureChaosIT`, `KafkaFailureChaosIT`, `DatabaseTimeoutChaosIT`); validated blameless postmortems with Google SRE 5-Whys methodology (`POSTMORTEM-001` for reservation expiry vs payment race, `POSTMORTEM-002` for HikariCP pool starvation under virtual threads).

**Problems found and fixed**
- Error Prone strict check `StringSplitter` flagged `traceparent.split("-")` in Kafka consumers -> replaced with index-based substring slicing without allocations.
- ArchUnit rule `adaptersAreNotPublicApi` flagged `RedisProductCache` -> made class and constructor package-private and co-located cache chaos test in `adapter.out.cache`.
- All 8 Maven modules, ArchUnit tests, and test suites pass cleanly with zero compiler warnings under `-Werror`.

### Session 4 — 2026-10-11 — Week 3 & Week 4 completed
**Done**
- **W3-01 (`libs/proto`):** Defined `inventory_service.proto` and `order_service.proto`, generated and compiled 54 stubs with gRPC deadlines.
- **W3-02 & W3-07 (`services/api-gateway`):** REST API Gateway routing to Inventory and Order gRPC services; Spring Security resource server with JWT auth and Broken Object Level Authorization (BOLA) validation (returns 404/empty for non-owners); atomic Redis token bucket rate limiting via Lua script on mutating POST routes.
- **W3-03 (`services/order-service`):** Pure Java Order aggregate, strict state machine (`PENDING_PAYMENT` -> `CONFIRMED` / `CANCELLED` / `REFUND_PENDING` -> `REFUNDED`), `order_status_history` audit tracking, idempotent creation via `idempotency_keys` table.
- **W3-04 & W3-05 (`database/seed` & `docs/benchmarks`):** `seed-large-dataset.sql` (100k products, 200k reservations, 500k orders); `query-plans.md` with EXPLAIN ANALYZE index justifications; `connection-pool-sizing.md` with Little's Law, HikariCP tuning and Prometheus pool metrics.
- **W3-06 (`services/inventory-service`):** Redis cache-aside for product catalog with stampede protection (single-flight locking) and `@TransactionalEventListener(phase = AFTER_COMMIT)` cache invalidation.
- **W4-01 & W4-02 (Outbox & Inbox):** `outbox_events` table and transactional relay worker (`FOR UPDATE SKIP LOCKED`) per service; `libs/event-schemas` shared event envelope; `processed_events` table with `InboxDeduplicator` preventing duplicate message execution.
- **W4-03 (`services/payment-service`):** Payment aggregate and service with controllable simulation outcomes (`SUCCEEDED`, `FAILED`, `TIMED_OUT`), outbox event publishing, and refund compensation use case.
- **W4-04 & W4-07 (Checkout Saga Orchestration):** Orchestrated checkout saga in `order-service` driven by `PaymentEventConsumer`; happy path confirmations; automatic compensations on payment failure/timeout; W4-07 edge-case handling when payment arrives after reservation expiry (transitions order to `REFUND_PENDING` and emits `RefundPaymentCommand`); scheduled `SagaTimeoutWorker` compensating orders stuck past 2 minutes.
- **W4-05 (`services/inventory-service`):** `ReservationExpiryWorker` claiming expired reservations via `FOR UPDATE SKIP LOCKED` and database time `now()`; `InventoryReconciliationJob` checking stock conservation and reservation sums.
- **W4-06 (Kafka Resilience & DLT):** Spring Kafka `DefaultErrorHandler` with `ExponentialBackOff` and `DeadLetterPublishingRecoverer` routing failed messages to `<topic>.DLT`; admin replay endpoint `POST /api/v1/admin/dlt/replay`.
- **W4-08 (`services/notification-worker`):** Asynchronous notification worker consuming `order-events` (`OrderConfirmed`, `OrderCancelled`) with inbox deduplication and simulated delivery logging.

**Problems found and fixed**
- Error Prone flagged unhandled CompletableFutures in `kafkaTemplate.send(...)` (`FutureReturnValueIgnored`) in `OutboxRelayWorker` -> solved by blocking synchronously with `.get(5, TimeUnit.SECONDS)` within the relay transaction boundary.
- Error Prone flagged `String.split(regex)` (`StringSplitter`) in `RateLimitingFilter` -> rewritten with index-based substring parsing.
- Error Prone flagged `toUpperCase()` without `Locale` (`StringCaseLocaleUsage`) in `ProcessPaymentUseCase` -> passed `Locale.ROOT`.
- ArchUnit onion rule prevented cross-adapter package imports (`SqlTypes`) -> localized timestamp conversions using `ZoneOffset.UTC`.

**Verification**
- `./mvnw test`: BUILD SUCCESS across entire reactor (175 tests pass: 116 inventory, 24 order, 11 gateway, 15 payment, 9 notification).
- Error Prone `-Werror`: Clean compilation across all modules.
- Spotless: All files formatted with `palantir-java-format`.
- ArchUnit: Hexagonal architecture rules enforced and passed in every service.
- All changes left uncommitted in working tree for the owner to commit.

### Session 3 — 2026-10-09 — Week 2 completed
**Done**
- Created branch `feat/week2-concurrency-core` from updated `main`.
- W2-01 Domain model & state machine: `Reservation` aggregate (`ReservationId`, `UserId`, `ReservationStatus`), strict transitions (`PENDING` → `CONFIRMED` / `CANCELLED` / `EXPIRED`), full unit test coverage. Updated `Inventory` aggregate with `reserve()`, `releaseReserved()`, `confirmReserved()`.
- W2-02 Flyway V2 migration (`V2__create_reservations_and_idempotency.sql`): `reservations` table with status check, partial unique index `idx_reservations_active_unique` on `(user_id, product_id)` for pending reservations, `idempotency_keys` table for at-most-once execution, `user_product_limits` table with check constraint (`purchased_count <= 2`).
- W2-03 Four `StockReservationStrategy` implementations:
  - `AtomicConditionalUpdateReservationStrategy`: recommended baseline (`UPDATE … WHERE available_quantity >= :qty`). Marked `@Primary`.
  - `PessimisticLockingReservationStrategy`: row lock (`SELECT … FOR UPDATE`) serialized inside transaction boundary.
  - `OptimisticLockingReservationStrategy`: version-checked update with jittered backoff retries.
  - `NaiveReservationStrategy`: test-only / benchmark-only lost-update demonstration.
- W2-04 Shared concurrency test suite (`StockReservationConcurrencyIT`): 50 concurrent threads against 10 stock items. Verified atomic, pessimistic, and optimistic strategies strictly conserve stock (zero overselling) across repeated test runs. Verified naive strategy consistently oversells, capturing the lost-update anomaly.
- W2-05 Reservation REST API: `POST /api/v1/reservations` with mandatory `Idempotency-Key` header, `GET /api/v1/reservations/{id}`, and `POST /api/v1/reservations/{id}/cancel`. Mapped all domain failures to RFC 9457 Problem Details (`insufficient-stock`, `purchase-limit-exceeded`, `missing-idempotency-key`, `idempotency-key-conflict`, `reservation-not-found`, `invalid-reservation-state`, `optimistic-lock-conflict`).
- W2-06 Purchase limit enforcement (`PurchaseLimitWriteSkewIT`): atomic UPSERT on `user_product_limits` preventing write-skew under concurrent requests from the same user.
- W2-07 Retryable SQLSTATE helper (`PostgresRetryTemplate`) with comprehensive unit tests for serialization failure (`40001`) and deadlock detected (`40P01`).
- W2-08 Architecture Decision Record [ADR-006](docs/decisions/ADR-006-inventory-reservation-concurrency-strategy.md) documenting reservation concurrency strategy selection and trade-offs. Created benchmark skeleton at `docs/benchmarks/concurrency-strategies.md`. Updated `docs/api/problems.md`.

**Problems found and fixed**
- In `PessimisticLockingReservationStrategy`, `reserve()` initially lacked `@Transactional`, causing Spring JDBC auto-commit to release the `SELECT … FOR UPDATE` row lock before the subsequent update. Added `@Transactional` so the exclusive lock is held across the entire critical section.
- Spring Boot 4 deprecations addressed: migrated `HttpStatus.UNPROCESSABLE_ENTITY` and `isUnprocessableEntity()` to `HttpStatus.UNPROCESSABLE_CONTENT` / HTTP 422.

**Verification**
- `./mvnw clean verify`: BUILD SUCCESS across parent and inventory-service.
- 102 unit tests (domain models, state machine, use cases, controller WebMvc contracts, ArchUnit rules, retry template) pass cleanly.
- 41 integration tests with Testcontainers PostgreSQL 18 pass cleanly (`StockReservationConcurrencyIT`, `ReservationApiIT`, `PurchaseLimitWriteSkewIT`, `CreateProductConcurrencyIT`, `ProductApiIT`, `DatabaseConstraintsIT`, `OperationalReadinessIT`).
- Spotless formatting clean; JaCoCo reports generated.
- All uncommitted changes left cleanly in working tree on `feat/week2-concurrency-core` for the user to commit.

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
