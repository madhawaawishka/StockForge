# StockForge — High-Concurrency Inventory & Reservation Platform

[![CI](https://github.com/madhawaawishka/StockForge/actions/workflows/ci.yml/badge.svg)](https://github.com/madhawaawishka/StockForge/actions/workflows/ci.yml)

**How do you sell 100 units to 50,000 people who all click "buy" in the same second — without overselling, double-charging or losing an order?**

StockForge is a backend system built to answer that question with evidence: reproducible concurrency tests, load benchmarks, failure experiments and documented trade-offs. It is a portfolio project that deliberately goes beyond CRUD.

> **Status: work in progress (Week 1 of 6).** The inventory service's product catalog is complete and tested. Reservations, orders, payments, the checkout saga, caching, events and benchmarks follow — see [PROGRESS.md](PROGRESS.md). Results below will only ever show numbers that were actually measured.

## What it will demonstrate
| Problem | Approach | Status |
| :--- | :--- | :--- |
| Overselling under contention | Pessimistic vs optimistic vs atomic conditional update, benchmarked | Planned (Week 2) |
| Data integrity | Invariants enforced in the domain, in PostgreSQL constraints and by tests | ✅ Stock conservation |
| Duplicate requests | Idempotency keys stored atomically with the business write | Planned (Week 2–3) |
| Cross-service consistency | Orchestrated saga with compensations; schema-per-service ownership | ✅ Ownership · Saga planned |
| Reliable events | Transactional outbox, idempotent consumers, retries and DLQ | Planned (Week 4) |
| Read scalability | Redis cache-aside with stampede protection | Planned (Week 3) |
| Operability | Health probes, Prometheus metrics, structured logs, tracing | ✅ Probes, metrics, logs |
| Evidence | k6 load tests, failure injection, ADRs, postmortems | ✅ ADRs · rest planned |

## Architecture
Three services (inventory, order, payment) behind an API gateway, each owning its own PostgreSQL schema, communicating over gRPC and Kafka. Each service is internally hexagonal: a pure-Java domain at the centre, adapters at the edges, and the dependency rules enforced by ArchUnit tests ([ADR-004](docs/decisions/ADR-004-hexagonal-architecture.md)).

The full design — non-functional requirements, data model, concurrency strategies, saga, failure modes — is in the [specification](high_concurrency_inventory_reservation_project_specification.md).

## Tech stack
Java 21 · Spring Boot 4.1 · PostgreSQL 18 · Flyway · Redis 8 · Apache Kafka 4 (KRaft) · JUnit 6 · Testcontainers · ArchUnit · Docker Compose · GitHub Actions

## Quick start
**Prerequisites:** JDK 21+ and Docker (Docker Desktop on Windows/macOS).

```bash
# 1. Start PostgreSQL, Redis and Kafka
docker compose up -d --wait

# 2. Run the inventory service against them
./mvnw -pl services/inventory-service spring-boot:run -Dspring-boot.run.profiles=local
```
On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

**Try it:**
```bash
# Create a product with 100 units of stock
curl -i -X POST http://localhost:8081/api/v1/admin/products \
  -H 'Content-Type: application/json' \
  -d '{"sku": "SNEAKER-RED-42", "name": "Limited Edition Sneaker",
       "price": {"amount": "19.99", "currency": "USD"}, "initialStock": 100}'

# List products (keyset-paginated) and check availability
curl http://localhost:8081/api/v1/products?limit=20
curl http://localhost:8081/api/v1/products/{id}/availability
```
- API documentation (Swagger UI): http://localhost:8081/swagger-ui.html — enabled by the `local` profile, off by default elsewhere
- Health: http://localhost:8081/actuator/health/readiness · Metrics: http://localhost:8081/actuator/prometheus
- Every error is RFC 9457 Problem Details; problem types are documented in [docs/api/problems.md](docs/api/problems.md).

## Configuration
Outside the `local` profile, the inventory service is configured only through environment variables, and it refuses to start if a required one is missing.

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `INVENTORY_DB_URL` | — (required) | JDBC URL, e.g. `jdbc:postgresql://db:5432/stockforge` |
| `INVENTORY_DB_USERNAME` | — (required) | Database role that owns the `inventory` schema |
| `INVENTORY_DB_PASSWORD` | — (required) | Its password (from a secret store, never from Git) |
| `INVENTORY_DB_POOL_SIZE` | `10` | Fixed connection-pool size |
| `INVENTORY_HTTP_PORT` | `8081` | HTTP port |
| `INVENTORY_API_DOCS_ENABLED` | `false` | Expose `/v3/api-docs` and Swagger UI |

Logs are structured JSON (Elastic Common Schema) by default and plain text under the `local` profile.

## Testing
```bash
./mvnw verify   # format check, unit tests, Testcontainers integration tests, coverage report
```
| Suite | What it proves |
| :--- | :--- |
| Domain & use-case unit tests | Business rules, with in-memory fakes — no Spring, no database |
| HTTP contract tests | Status codes, headers, JSON shape and Problem Details for every error path |
| Architecture tests | Hexagonal dependency rules cannot be broken silently |
| Integration tests (real PostgreSQL 18) | Full API over HTTP, database constraints reject invalid data even when SQL bypasses the domain, 16 concurrent creates of one SKU yield exactly one product |

Integration tests need Docker running. Coverage report: `services/inventory-service/target/site/jacoco/index.html`.

## Project documentation
- [Specification](high_concurrency_inventory_reservation_project_specification.md) — requirements, architecture, roadmap
- [Architecture decision records](docs/decisions/README.md)
- [API problem types](docs/api/problems.md)
- [Progress tracker](PROGRESS.md)
