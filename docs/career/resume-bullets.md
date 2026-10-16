# Resume entry (W6-05)

Every number below was measured on 2026-10-14 and is documented in [docs/benchmarks](../benchmarks/README.md); every
claim points to code or a test you can open in an interview. Re-measure before quoting numbers from a newer run.

## Entry — pick 4 or 5 bullets

**StockForge — High-Concurrency Inventory & Reservation Platform** · Java 21, Spring Boot 4, PostgreSQL, Redis, Kafka,
gRPC, Docker, Terraform (AWS) · github.com/madhawaawishka/StockForge

- Built a flash-sale reservation system across 5 microservices that sold **exactly 500 of 500 units to 5,000
  concurrent buyers at 500 req/s with 0 oversells**, using an atomic conditional `UPDATE` and database-enforced stock
  invariants; reservation **P99 119–254 ms** through the API gateway.
- Benchmarked atomic, pessimistic and optimistic locking on one hot row; showed that pessimistic and optimistic
  locking **exhaust the connection pool (up to 191 waiting requests)** and that optimistic locking *undersells*, and
  kept the atomic update as the documented default.
- Designed an **orchestrated checkout saga over Kafka** with a transactional outbox, idempotent consumers keyed by
  event id, dead-letter topics with replay, and refunds for payments that arrive after a reservation expires —
  verified end to end against real PostgreSQL and Kafka with Testcontainers.
- Added **fault-injection tests with Toxiproxy** (Redis outage, database partition, Kafka pause, shutdown mid-request);
  they exposed a database partition being reported as 500 instead of 503, which was fixed and is now guarded by CI.
- **Profiled the gateway with Java Flight Recorder** under load and found a 1-CPU container limit silently selecting
  the Serial GC and a single virtual-thread carrier; fixing the JVM sizing turned a 5–7 s P99 into 230 ms. Switching
  the gateway to virtual threads cut P99 from 383–955 ms to 119–217 ms at the same load.
- Cut the background workers' polling queries from **55 ms and 328 ms to 0.8 ms and 0.4 ms** on 0.5–1.15 M-row tables
  with partial indexes, validated with `EXPLAIN ANALYZE` on a seeded dataset.
- Instrumented the platform with Micrometer/OpenTelemetry (traces propagated through Kafka), SLO **burn-rate alerts
  covered by `promtool` unit tests**, Grafana dashboards and runbooks; shipped non-root, read-only container images and
  a validated Terraform design for ECS Fargate, RDS, ElastiCache and MSK.

## Shorter variant (three lines)
- Built a 5-service flash-sale platform (Java 21, Spring Boot, PostgreSQL, Kafka, Redis) that sold exactly 500/500
  units to 5,000 concurrent buyers with zero oversell and a 119–254 ms P99, using atomic conditional updates.
- Implemented a Kafka checkout saga with transactional outbox, idempotent consumers and DLT replay; proved failure
  handling with Toxiproxy chaos tests that caught and fixed a real error-mapping defect.
- Profiled with JFR and fixed JVM container sizing (P99 5–7 s → 230 ms); partial indexes cut worker queries
  from 328 ms to 0.4 ms on 1.15 M rows.

## Before you use these
- The load generator and every service ran on one laptop (Ryzen 7 6800H). Say so if asked; it is a strength that you
  know it.
- The sold-out P99 objective (< 50 ms) was **not** met at 500 req/s (25–119 ms). Know why (queueing behind the hot
  row in the first second of the sale) and what you would do (gateway-side sold-out flag).
- "5 microservices" — gateway, inventory, order, payment, notification. Be ready to argue why that split, and why not
  fewer (ADR-002, ADR-007).
