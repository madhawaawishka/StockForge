# CLAUDE.md — StockForge working agreement

StockForge is a portfolio-grade high-concurrency inventory & reservation platform (Java 21, Spring Boot 4, PostgreSQL, Redis, Kafka).
The full specification is [high_concurrency_inventory_reservation_project_specification.md](high_concurrency_inventory_reservation_project_specification.md).

## Session protocol (mandatory)
- **Start:** read [PROGRESS.md](PROGRESS.md) first. It records the active branch, build status and the next task. Continue from there.
- **End:** run `./mvnw verify`, then update PROGRESS.md (checklist, session log, current status, next steps). Report test results honestly — never mark a task done unless it is verified. `./mvnw test` alone is never enough: it does not start a Spring context.
- **CI:** after the owner pushes, check that the *CI* workflow's jobs actually ran their steps (job annotations via the GitHub API); green Dependabot runs are not CI.
- **Commits:** the project owner commits and pushes. Leave changes uncommitted and hand over suggested Conventional Commit messages, unless the owner explicitly asks you to commit.

## Commands
| Purpose | Command |
| :--- | :--- |
| Full build (format check, unit + integration tests, coverage) | `./mvnw verify` |
| Unit tests only | `./mvnw test` |
| Auto-format code | `./mvnw spotless:apply` |
| Start local infrastructure | `docker compose up -d` |
| Run the inventory service locally | `./mvnw -pl services/inventory-service spring-boot:run -Dspring-boot.run.profiles=local` |
| Whole system in containers | `docker compose --profile apps up -d --build --wait` (`GATEWAY_PUBLIC_PORT=8088` if 8080 is taken) |
| Smoke / load test (k6) | `docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 -v "$PWD/load-tests:/scripts" grafana/k6:1.3.0 run /scripts/smoke.js` |
| Database invariant check | `bash scripts/verify-invariants.sh` (exits non-zero on a violation) |
| README promises against a running stack | `bash scripts/verify-readme.sh` (`BASE_URL=…` / `COMPOSE_PROJECT_NAME=…` for a non-default stack) |
| Dev JWT | `python scripts/dev-token.py [--admin]` |
| Alert-rule tests | `docker run --rm -v "$PWD/observability/prometheus:/cfg" -w /cfg --entrypoint promtool prom/prometheus:v3.5.0 test rules alert_rules_test.yml` |
| Terraform checks | `terraform fmt -check -recursive` in `infra/terraform`, `terraform init -backend=false && terraform validate` in `environments/dev` |

Ports: gateway HTTP 8080 (management 8180), inventory 8081 / gRPC 9091, order 8082 / gRPC 9092, payment 8083,
notification 8084; PostgreSQL 5432, Redis 6379, Kafka 9092 (host) / 19092 (containers), Grafana 3000, Prometheus 9090,
Kafka UI 8090. Avoid 9090 for anything else.

Integration tests (`*IT.java`) use Testcontainers and need Docker Desktop running. On Windows use Git Bash for `./mvnw` (or `mvnw.cmd` in PowerShell).

Windows notes:
- In Git Bash, prefix `docker … /absolute/container/path` commands with `MSYS_NO_PATHCONV=1`, or the path is rewritten to a Windows path.
- `spring-boot:run` forks the application JVM; stopping Maven does not stop it. Free port 8081 by stopping the process that listens on it.
- Port 8080 may be held by Windows HTTP.sys (PID 4); publish the gateway elsewhere with `GATEWAY_PUBLIC_PORT`.
- Shell scripts created on Windows are committed without the executable bit (this kept CI from ever running, POSTMORTEM-001): run `git update-index --chmod=+x <file>` for new scripts, and call scripts with `bash` in workflows.
- Large downloads inside Docker (Maven artifacts, Terraform providers) occasionally break off; retry, or download on the host and mount (e.g. `terraform init -plugin-dir`).
- Other local projects may run containers named `api-gateway`, `order-service`, … — always address StockForge containers through `docker compose` (project `stockforge`).

## Architecture rules (enforced by ArchUnit — do not break them)
Each service uses hexagonal architecture under `com.stockforge.<service>`:
- `domain.model` — aggregates, value objects, domain events. **Pure Java**: no Spring, Jakarta, JDBC or Jackson imports.
- `domain.port` — interfaces the domain/application need (repositories, publishers).
- `application` — use cases and transaction boundaries. May use only `org.springframework.transaction.annotation` from Spring.
- `adapter.in.*` (web, grpc, kafka) and `adapter.out.*` (persistence, messaging) — framework code lives here only.
- `config` — Spring wiring. Use cases are registered as `@Bean`s here, not annotated with stereotypes.
- Services never share domain code and never touch another service's database schema. Only contracts (proto, event schemas) are shared, plus generic infrastructure in `libs/messaging` (outbox, inbox, dead letters — ADR-013) that knows no business types.
- Inbound adapters classify outages with `DependencyFailures` (503 / `UNAVAILABLE`); do not map database exceptions ad hoc.

## Coding conventions
- Expected business outcomes are returned as sealed result types; exceptions are for bugs and infrastructure failures.
- Value objects are records that validate in their constructor.
- Inject `java.time.Clock`; never call `Instant.now()` directly in domain/application code. Truncate timestamps to microseconds (PostgreSQL precision).
- SQL lives in persistence adapters (Spring `JdbcClient`); always parameterized. Bind timestamps as `OffsetDateTime` (UTC).
- Money is `BigDecimal` + `Currency` (never floating point); JSON amounts are strings.
- No network calls inside a database transaction. Every remote call has a timeout.
- REST errors use RFC 9457 Problem Details; problem types are documented in [docs/api/problems.md](docs/api/problems.md).
- Configuration through typed `@ConfigurationProperties` and environment variables. Secrets never go in Git; local-only credentials live in the `local` profile and `docker-compose.yml`.
- Comments explain *why*. Link non-obvious decisions to their ADR in `docs/decisions/`.

## Testing conventions
- `*Test.java` — fast unit tests (Surefire). `*IT.java` — integration tests with Testcontainers (Failsafe).
- Prefer in-memory fakes over mocks for ports; use Mockito only at adapter boundaries.
- No `Thread.sleep` in tests — use Awaitility. Concurrency tests release threads with a latch and are repeated.
- Every invariant is enforced in the domain, in the database, and by a test.

## Git workflow
- One branch per milestone or feature (`feat/…`, `fix/…`, `docs/…`); never commit directly to `main`.
- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `build:`, `ci:`, `refactor:`, `perf:`), referencing task IDs from PROGRESS.md (e.g., `feat(inventory): add product catalog (W1-07)`).
- Do not commit, push or open PRs unless the project owner asks.
