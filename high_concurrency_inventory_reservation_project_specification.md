# HIGH-CONCURRENCY INVENTORY & RESERVATION PLATFORM

**A-to-Z Project Specification, Architecture, Engineering Principles & Interview Guide**

*Based on the reference document: High_Concurrency_Inventory_Reservation_Project_Specification.docx*

**Recommended implementation:** Java + Spring Boot  
**Portfolio objective:** demonstrate production-oriented backend engineering, concurrency, database depth, distributed systems, reliability, scalability, and observability.

| Item | Decision |
| :--- | :--- |
| Primary goal | Build one standout engineering project for Software Engineer / Backend / Full-Stack applications |
| Recommended language | Java (Java 21 or Java 25 LTS) |
| Backend framework | Spring Boot |
| Primary database | PostgreSQL |
| Cache / coordination | Redis |
| Event streaming | Apache Kafka |
| Service communication | REST externally; gRPC internally |
| Testing | JUnit 5, Mockito, Testcontainers |
| Load testing | k6 |
| Observability | OpenTelemetry, Prometheus, Grafana, structured logs |
| Containerization | Docker / Docker Compose |
| Cloud / IaC | AWS + Terraform (optional production-style deployment) |
| CI/CD | GitHub Actions |
| Target build time | ~6 weeks |
| Local development cost | Can be kept at $0 by running the stack locally |

---

## Contents
1. Executive Overview
2. What Problem Does This Project Solve?
3. What the System Is Supposed to Do
4. Why This Project Is High-Impact for a Resume
5. Scope and Non-Goals
6. High-Level Architecture
7. Recommended Technology Stack
8. Core Services and Responsibilities
9. Functional Requirements
10. Database Design
11. Concurrency Control
12. Transaction and Consistency Strategy
13. Redis Caching Strategy
14. Kafka and Event-Driven Architecture
15. Idempotency
16. Transactional Outbox Pattern
17. Reservation Expiry and Recovery
18. Retries, Dead-Letter Handling and Failure Recovery
19. Rate Limiting and Backpressure
20. Connection Pools and Resource Management
21. Observability
22. Load Testing and Benchmarking
23. Failure Injection / Chaos Testing
24. Security
25. Testing Strategy
26. Repository / Folder Structure
27. Local Development Environment
28. AWS and Terraform Architecture
29. CI/CD
30. Six-Week Implementation Roadmap
31. Definition of Done
32. What to Put in the GitHub README
33. Resume Entry
34. Interview Topics and Questions
35. What This Project Proves About You
36. What NOT to Over-Engineer
37. Final Recommended Stack

---

## 1. Executive Overview
The High-Concurrency Inventory & Reservation Platform is a backend-heavy distributed system designed to model a difficult real-world problem: many customers competing for a limited quantity of inventory at the same time. The portfolio value comes from the engineering constraints rather than from a large UI.

The system should behave correctly when hundreds or thousands of purchase or reservation requests arrive concurrently. It must prevent overselling, avoid duplicate orders during retries, recover from partial failures, process work asynchronously, and remain observable and measurable under load.

This project is intentionally different from a normal CRUD application. Its main purpose is to demonstrate that you can reason about correctness, performance, consistency, failure modes, and trade-offs.

*   **Primary scenario:** flash-sale / limited-inventory reservation and checkout.
*   **Primary engineering question:** how do we keep inventory correct while many requests compete for the same scarce resource?
*   **Secondary engineering questions:** how do we cache safely, publish events reliably, survive worker/API failures, and prove the system scales?

## 2. What Problem Does This Project Solve?
Imagine a product has only 100 units available. At the moment a sale starts, thousands of users attempt to buy it. A naive application can read the same stock value in many requests, decrement it independently, and accidentally oversell.

*   Request A reads stock = 1
*   Request B reads stock = 1
*   Request A writes stock = 0
*   Request B writes stock = 0
*   **Result:** two orders may exist for one physical unit.

The project solves this class of problem by making inventory a protected resource and explicitly designing the system around database transactions, concurrency control, idempotency, caching, asynchronous events, retries, and failure recovery.

**The business-style problem in one sentence:**  
Safely reserve and sell scarce inventory under high concurrent traffic without overselling, duplicating orders, or losing important events.

## 3. What the System Is Supposed to Do
The platform provides a small commerce/reservation flow with enough complexity to expose production-grade engineering challenges.

| Flow | Expected behavior |
| :--- | :--- |
| Browse products | Return product information quickly, preferably from cache after the first request. |
| Check availability | Return current availability while making the source of truth clear. |
| Create reservation | Atomically reserve inventory if sufficient quantity exists. |
| Create order | Create one logical order even if the client retries. |
| Process payment | Model an asynchronous payment workflow and explicit success/failure states. |
| Confirm order | Transition reservation/order states safely and emit events. |
| Expire reservation | Automatically release stock when a reservation passes its expiry time. |
| Notify customer | Process notifications asynchronously and recover from temporary failures. |
| Observe system | Expose logs, metrics, traces and operational dashboards. |

## 4. Why This Project Is High-Impact for a Resume
Your existing resume already demonstrates TypeScript/NestJS, PostgreSQL, AWS, microservices, message-driven communication, Kafka-related work, and AI/RAG systems. This project should therefore add a different kind of evidence: deep systems engineering.

| Current profile evidence | New project should add |
| :--- | :--- |
| NestJS / Node / TypeScript backend | Java / Spring Boot backend |
| Microservices / REST | Concurrency, transactional boundaries, gRPC |
| PostgreSQL usage | Indexes, EXPLAIN ANALYZE, isolation, row locks, pooling |
| Messaging / Kafka | Reliable event publication, retries, idempotent consumers, DLQ |
| AWS | Local-first architecture first; production-style AWS deployment later |
| Feature delivery | Benchmark-driven performance and reliability engineering |

The strongest resume signal is not the number of services. It is the fact that you can demonstrate an engineering problem, reproduce it under load, explain the chosen solution, and show measurements before and after the optimization.

## 5. Scope and Non-Goals

**In scope**
*   Product catalog and inventory
*   Reservations and order lifecycle
*   Concurrency control
*   Transactions and isolation
*   PostgreSQL indexing and query optimization
*   Redis caching and rate limiting
*   Kafka event workflows
*   Idempotency
*   Transactional outbox
*   Retries and dead-letter processing
*   Reservation expiry
*   Load testing
*   Observability
*   Failure injection
*   Docker local environment
*   Optional AWS/Terraform deployment
*   CI/CD

**Out of scope**
*   A polished consumer-grade storefront
*   Real financial payment processing with real money
*   Complex recommendation systems
*   Full warehouse management
*   Multi-region active-active deployment
*   A massive Kubernetes platform from day one

The project is a portfolio engineering system, not a production business that must be launched to real customers.

## 6. High-Level Architecture
```text
                    Web Client
                         |
                       REST
                         |
                 +-------v-------+
                 |  API Gateway  |
                 |  Spring Boot  |
                 +-------+-------+
                         |
          +--------------+---------------+
          |              |               |
        gRPC           gRPC            gRPC
          |              |               |
   +------v------+ +-----v------+ +------v------+
   |  Inventory  | |   Order    | |   Payment   |
   |   Service   | |  Service   | |   Service   |
   +------+------+ +-----+------+ +-------------+
          |              |               |
          |          PostgreSQL          |
          |              |               |
          +--------------+---------------+
                         |
                       Redis
                         |
                       Kafka
                         |
        +----------------+---------------+
        |                |               |
     Workers          Workers         Workers
    Inventory         Payment       Notification
```

**Recommended implementation principle:** start with three core services and keep the deployment simple. Do not create a microservice for every table. Service boundaries should exist because of responsibility, scaling, failure isolation, or ownership—not because microservices look impressive.

## 7. Recommended Technology Stack

| Layer | Recommended technology | Why it is used |
| :--- | :--- | :--- |
| Language | Java 21 or Java 25 LTS | Adds a new backend ecosystem to the resume while remaining highly relevant to enterprise/backend roles. |
| Framework | Spring Boot | Production-oriented HTTP, dependency injection, validation, transactions, actuator and ecosystem support. |
| External API | REST / JSON | Simple client integration and easy demonstration. |
| Internal API | gRPC + Protocol Buffers | Strongly typed service-to-service communication and a useful distributed-systems learning opportunity. |
| Database | PostgreSQL | Transactions, locking, isolation, indexes, query planning and a strong relational consistency model. |
| DB access | Spring Data JPA + Spring JDBC | Use JPA where convenient; use JDBC/native SQL where understanding and controlling SQL matters. |
| Cache | Redis | Cache-aside reads, rate limiting, idempotency support and selective coordination. |
| Streaming | Apache Kafka | Asynchronous events, consumer groups, ordering considerations and replay/recovery concepts. |
| Testing | JUnit 5 + Mockito + Testcontainers | Unit, integration and realistic dependency-backed tests. |
| Load testing | k6 | Controlled concurrent traffic and latency/error benchmarking. |
| Observability | OpenTelemetry + Prometheus + Grafana | Tracing, metrics and operational dashboards. |
| Logging | Structured JSON logs | Machine-readable production-style logs with correlation IDs. |
| Containerization | Docker + Docker Compose | Reproducible local environment. |
| Cloud | AWS | Cloud deployment and operational familiarity. |
| IaC | Terraform | Repeatable infrastructure provisioning. |
| CI/CD | GitHub Actions | Automated test/build pipeline and optional deployment. |

*Why Java instead of Go for this project:* Go is excellent for concurrency and systems programming, but Java gives this particular portfolio project a strong combination of a new language for you, Spring/enterprise backend exposure, broad hiring relevance, and enough tooling to demonstrate concurrency, transactions, messaging and observability deeply. Go remains a good future learning target for a dedicated concurrency/systems project.

## 8. Core Services and Responsibilities

| Service | Responsibilities |
| :--- | :--- |
| API Gateway / API service | Authentication, request routing, rate limits, idempotency boundary and API aggregation where needed. |
| Inventory Service | Inventory source of truth, reservations, stock decrement/release, locking and concurrency rules. |
| Order Service | Order creation, order state machine, order history and idempotency-aware order commands. |
| Payment Service | Simulated payment workflow with asynchronous outcomes, timeouts and retryable failures. |
| Inventory Worker | Reservation expiry and background reconciliation tasks. |
| Notification Worker | Email/notification simulation, retries and dead-letter handling. |

*You may merge API Gateway + Order Service during early development. The architecture is allowed to evolve as the project matures.*

## 9. Functional Requirements
*   **Products:** Create/list/get products with price and inventory metadata.
*   **Inventory:** Query availability and maintain available/reserved quantities.
*   **Reservations:** Create, confirm, cancel and expire reservations.
*   **Orders:** Create an order from a valid reservation; expose current status and history.
*   **Payments:** Simulate asynchronous payment success, failure, timeout and retry.
*   **Events:** Publish domain events for state transitions.
*   **Notifications:** Send simulated confirmation/failure notifications asynchronously.
*   **Admin operations:** Optional endpoints for stock adjustment and event replay/reconciliation.

## 10. Database Design
PostgreSQL is the source of truth for inventory and transactional business state. Redis is not the authoritative inventory database.

| Table | Important columns / purpose |
| :--- | :--- |
| `users` | id, email, created_at |
| `products` | id, sku, name, price, status, created_at |
| `inventory` | product_id, available_quantity, reserved_quantity, version, updated_at |
| `reservations` | id, user_id, product_id, quantity, status, expires_at, created_at |
| `orders` | id, user_id, status, total_amount, created_at, updated_at |
| `order_items` | order_id, product_id, quantity, unit_price |
| `payments` | id, order_id, status, provider_reference, created_at, updated_at |
| `idempotency_keys` | key, user_id, request_hash, status, response_payload, created_at |
| `outbox_events` | id, aggregate_type, aggregate_id, event_type, payload, status, created_at, published_at |
| `audit_logs` | id, actor_id, action, aggregate_type, aggregate_id, metadata, created_at |

**Important inventory invariant**
*   `available_quantity >= 0`
*   `reserved_quantity >= 0`
*   `available_quantity + reserved_quantity <= total_quantity` (when `total_quantity` is modeled)

Choose one canonical representation and enforce it with application logic plus database constraints where practical.

## 11. Concurrency Control
This is the core learning objective. Two or more requests must never be allowed to race into an invalid inventory state.

### A. Pessimistic locking
```sql
BEGIN;
SELECT available_quantity
FROM inventory
WHERE product_id = ?
FOR UPDATE;

-- validate quantity
-- update inventory
COMMIT;
```
The row is locked during the transaction. This is straightforward to reason about for scarce shared inventory, but concurrent requests may wait on the same row.

### B. Optimistic locking
```sql
UPDATE inventory
SET available_quantity = available_quantity - 1,
    reserved_quantity = reserved_quantity + 1,
    version = version + 1
WHERE product_id = ?
  AND version = ?
  AND available_quantity > 0;
```
The update succeeds only if the version still matches. Conflicts are detected rather than serialized by waiting on the lock.

**Engineering experiment**
| Experiment | What to measure |
| :--- | :--- |
| Naive implementation | Demonstrate race condition / overselling. |
| Pessimistic locking | Success count, P95/P99 latency, lock contention. |
| Optimistic locking | Conflict rate, retries, P95/P99 latency, throughput. |

Do not claim one method is universally best. Explain why a method is appropriate for the workload and what trade-off you observed.

## 12. Transaction and Consistency Strategy
Use explicit transactional boundaries. Inventory reservation is not a collection of independent SQL statements; it is one business operation with invariants.
*   **Atomic reservation:** stock validation and stock mutation occur in one transaction.
*   **Order creation:** order state and related business records are committed consistently.

Do not pretend a database transaction automatically makes Kafka publication atomic. Use the outbox pattern for that cross-system reliability problem.

Define where strong consistency is required and where eventual consistency is acceptable.

**Isolation levels to understand**
*   READ COMMITTED
*   REPEATABLE READ
*   SERIALIZABLE

The project should include a small test or README experiment explaining which isolation behavior matters for the inventory use case and what additional contention stronger isolation may create.

## 13. Redis Caching Strategy
Use cache-aside for read-heavy product information. The database remains authoritative for inventory mutations.

```text
GET /products/123
        |
        v
     Redis hit? ---- yes ---> return cached value
        | no
        v
   PostgreSQL query
        |
        v
     Redis SET
        |
        v
     Response
```

| Use case | Recommendation |
| :--- | :--- |
| Product detail/list | Cache-aside with TTL. |
| Rate limiting | Redis counters / token-bucket-like mechanism. |
| Idempotency | Short-to-medium lived records or database-backed source of truth depending on desired guarantees. |
| Inventory truth | Do NOT rely on cached inventory as the authoritative write-side source. |

Measure cache hit ratio and response latency. Also document invalidation behavior and what happens if Redis is unavailable.

## 14. Kafka and Event-Driven Architecture
Use Kafka for work that does not need to block the user-facing request. Keep the synchronous transaction small and move non-critical follow-up processing to consumers.

| Topic | Example event |
| :--- | :--- |
| `order-events` | OrderCreated, OrderConfirmed, OrderCancelled |
| `inventory-events` | ReservationCreated, ReservationReleased, ReservationExpired |
| `payment-events` | PaymentSucceeded, PaymentFailed |
| `notification-events` | NotificationRequested |

**Consumer design goals:** consumer groups for horizontal scaling, explicit retry behavior, idempotent processing, visibility into lag, and a dead-letter path for poison messages.

## 15. Idempotency
Clients retry requests. Networks fail. Timeouts do not necessarily mean the server did nothing. Therefore, order-creating commands should accept an `Idempotency-Key`.

```text
POST /orders
Idempotency-Key: 7f3c...

First attempt -> create order -> store response
Retry with same key -> return original logical result
Different payload with same key -> reject as misuse
```

| Field | Purpose |
| :--- | :--- |
| key | Unique client-supplied identifier for the logical operation. |
| user_id | Bind the key to the caller. |
| request_hash | Prevent reusing the same key with a different payload. |
| response_payload | Return the original result on retry. |
| status | Track processing state. |

## 16. Transactional Outbox Pattern
The classic failure: the database transaction commits, but the Kafka publish fails. The business operation exists in PostgreSQL, but downstream consumers never hear about it.

```sql
BEGIN
  INSERT INTO orders ...
  INSERT INTO outbox_events ...
COMMIT
```
**Outbox worker**
1. read unpublished events
2. publish to Kafka
3. mark published

*Important interview point:* the outbox pattern makes event publication reliable relative to the database transaction, but consumers still need idempotency because retries can produce duplicate deliveries.

## 17. Reservation Expiry and Recovery
Reservations should have an expiry timestamp. When payment is not completed in time, the system releases the reserved stock.

```text
AVAILABLE
   | reserve
   v
RESERVED ---- payment success ----> CONFIRMED
   |
   | timeout / cancellation
   v
EXPIRED
   |
   v
stock released
```

Implement expiry as background work. Make expiry processing safe if multiple workers see the same reservation. This is another place to apply concurrency control and idempotent state transitions.

## 18. Retries, Dead-Letter Handling and Failure Recovery
Transient failures should be retried; permanent or poison messages should eventually stop retrying and become visible for manual investigation.

```text
Attempt 1 -> fail
Attempt 2 -> fail
Attempt 3 -> fail
             |
             v
        DEAD-LETTER PATH
```
*   Record retry count and last error.
*   Use bounded retries with backoff rather than infinite tight loops.
*   Make handlers idempotent.
*   Expose a metric for DLQ size.
*   Provide an admin/replay mechanism only after understanding duplicate-processing implications.

## 19. Rate Limiting and Backpressure
High traffic should not be allowed to consume all database or downstream capacity. Protect expensive endpoints and observe when the system approaches its limits.

**Example policy:**
*   `POST /orders` -> 100 requests/minute/user
*   `POST /reservations` -> stricter burst control
*   **Excess traffic -> 429 Too Many Requests**

The exact values are for experimentation. Tune them during load testing rather than treating the example as a production policy.

## 20. Connection Pools and Resource Management
The database may support thousands of HTTP requests while only a limited number of DB connections should be open. Configure the pool deliberately and observe queueing/latency under load.

*   Maximum pool size
*   Minimum idle / baseline connections
*   Connection timeout
*   Idle timeout
*   Transaction duration

*Learning objective:* understand that increasing database connections is not an automatic scaling strategy. Too many concurrent connections can create contention and exhaust database resources.

## 21. Observability
The project should be observable enough that you can answer “why is this request slow?” without reading code first.

| Metric / signal | Why it matters |
| :--- | :--- |
| HTTP request duration (P50/P95/P99) | User-visible latency and tail behavior. |
| Request / error rate | Overall service health. |
| Reservation conflict count | Concurrency pressure. |
| DB connection usage | Pool saturation. |
| DB query duration | Identify slow SQL. |
| Redis hit ratio | Validate cache effectiveness. |
| Kafka consumer lag | Detect processing backlog. |
| Retry count / DLQ count | Reliability problems. |
| Reservation expiry backlog | Background processing health. |

Add a correlation/request ID and propagate it through HTTP, gRPC, logs and asynchronous event metadata where practical.

## 22. Load Testing and Benchmarking
Performance claims should be measured, not guessed. Use k6 to reproduce realistic traffic and capture latency/error metrics.

| Scenario | Suggested load |
| :--- | :--- |
| Normal traffic | ~100 concurrent users |
| Busy traffic | ~500 concurrent users |
| High traffic | ~1,000 concurrent users |
| Reservation contention | ~2,000 concurrent purchase attempts against small stock |
| Stress / limit test | ~5,000–10,000 concurrent requests where the local machine/environment permits |

**Benchmark outputs:**
*   Requests per second
*   P50, P95, P99 latency
*   Error rate
*   Successful reservations
*   Overselling count
*   Database CPU / connections
*   Redis hit ratio
*   Kafka consumer lag

A strong portfolio README should include a small table or graph comparing “before optimization” versus “after optimization”.

## 23. Failure Injection / Chaos Testing
Deliberately break dependencies and prove the system behaves predictably.

| Failure | Expected lesson |
| :--- | :--- |
| Kafka unavailable | Outbox events remain durable and can be published after recovery. |
| Redis unavailable | Read path falls back safely; correctness does not depend on the cache. |
| Payment timeout | Order/reservation state remains consistent and retryable. |
| Worker crash | Work can be retried without creating duplicate business effects. |
| Duplicate event | Consumer idempotency prevents double application of state changes. |
| Database temporarily unavailable | Requests fail explicitly rather than silently corrupting state. |

## 24. Security
*   Authentication with JWT or a simple identity provider integration.
*   Authorization checks for user/admin operations.
*   Validate request payloads and reject malformed or unexpected fields.
*   Never place database credentials or secrets in Git.
*   Use environment variables / secret management.
*   Apply rate limiting to sensitive endpoints.
*   Log security-relevant actions without logging secrets.
*   Use TLS in a deployed environment.

Security is not the headline of this project, but it should be present enough to demonstrate responsible backend development.

## 25. Testing Strategy

| Test level | What to test |
| :--- | :--- |
| Unit | Inventory rules, state transitions, retry decisions, idempotency logic. |
| Integration | Real PostgreSQL queries, transactions, indexes, Redis behavior, Kafka integration. |
| Concurrency | Many threads/requests trying to reserve the same stock. |
| Contract | REST / gRPC request and response contracts. |
| End-to-end | Reserve -> order -> payment -> confirmation lifecycle. |
| Load | Sustained and burst traffic with k6. |
| Failure | Dependency outage, duplicate messages, worker restart, timeout scenarios. |

Use Testcontainers so integration tests run against real PostgreSQL, Redis and Kafka containers instead of mocks wherever realistic behavior matters.

## 26. Repository / Folder Structure
```text
high-concurrency-inventory-platform/
├── services/
│   ├── api-gateway/
│   ├── inventory-service/
│   ├── order-service/
│   └── payment-service/
├── workers/
│   ├── outbox-worker/
│   ├── reservation-expiry-worker/
│   └── notification-worker/
├── libs/
│   └── proto/
├── database/
│   ├── migrations/
│   └── seed/
├── load-tests/
│   ├── smoke.js
│   ├── reservation-contention.js
│   └── checkout.js
├── infra/
│   └── terraform/
├── observability/
│   ├── prometheus/
│   └── grafana/
├── docs/
│   ├── architecture/
│   ├── decisions/
│   └── benchmarks/
├── docker-compose.yml
├── README.md
└── .github/workflows/ci.yml
```
The exact monorepo structure can be changed. The important part is that the repository exposes architecture, tests, load tests, infrastructure and engineering documentation—not only application code.

## 27. Local Development Environment
Start local-first. This avoids spending money and keeps development fast.

**Docker Compose:**
*   PostgreSQL
*   Redis
*   Kafka
*   Prometheus
*   Grafana

Run Java/Spring Boot services either locally or in containers.
*Recommended local loop:* code locally -> run unit tests -> run Testcontainers integration tests -> run Docker Compose dependencies -> run k6 benchmark -> inspect Grafana/traces -> optimize -> repeat.

## 28. AWS and Terraform Architecture
Cloud deployment is an optional final stage. Do not make AWS a prerequisite for building the engineering core.

**AWS (illustrative)**
```text
├── ECS/Fargate        -> Spring Boot services / workers
├── RDS PostgreSQL     -> transactional database
├── ElastiCache Redis  -> cache / rate limiting
├── Amazon MSK         -> Kafka (or a managed equivalent if preferred)
├── CloudWatch         -> infrastructure logs/alarms
└── S3                  -> optional benchmark/result artifacts
```
Terraform should provision the infrastructure repeatably. For a portfolio project, the cloud environment can be temporary and scaled down or destroyed after demonstrations. Exact AWS cost depends on region, instance sizes, managed-service choices, traffic, retention and how long the environment remains running; keeping the main development stack local is the safest way to keep costs low.

## 29. CI/CD
Use GitHub Actions to demonstrate automated engineering discipline.

```text
Pull request / push
      ↓
format / static checks
      ↓
unit tests
      ↓
integration tests (Testcontainers)
      ↓
build JAR / Docker image
      ↓
optional deploy to AWS
```
Add a separate workflow for load tests so expensive performance runs are intentional rather than triggered on every commit.

## 30. Six-Week Implementation Roadmap

| Week | Primary work | Output |
| :--- | :--- | :--- |
| Week 1 | Learn Java/Spring fundamentals needed for the project; create services; PostgreSQL schema; basic REST APIs. | Running product/inventory/order skeleton. |
| Week 2 | Reservation workflow; transactions; pessimistic locking; optimistic locking; concurrency tests. | Correct inventory under concurrent writes. |
| Week 3 | Indexes; EXPLAIN ANALYZE; connection pools; Redis caching; idempotency. | Measured DB/cache improvements and duplicate-request protection. |
| Week 4 | Kafka events; consumers; outbox worker; retries; dead-letter handling; reservation expiry. | Reliable asynchronous workflow. |
| Week 5 | Observability; OpenTelemetry; Prometheus/Grafana; k6 load tests; failure injection. | Dashboards, traces and benchmark results. |
| Week 6 | Docker polish; Terraform; optional AWS deployment; CI/CD; README; architecture diagrams; resume bullets; interview preparation. | Portfolio-ready repository and evidence. |

*Time estimate:* about 4–6 weeks depending on daily hours. A serious part-time schedule around 2–4 hours/day fits the six-week target well. The project can be completed faster, but speed should not replace benchmarking, failure testing and documentation.

## 31. Definition of Done
*   Inventory cannot be oversold in the tested concurrency scenario.
*   Pessimistic and optimistic concurrency approaches have been compared or at least one is fully implemented with a documented rationale.
*   Order creation is idempotent.
*   Inventory mutation is transactionally protected.
*   Important queries have indexes justified by actual query plans.
*   Redis caching has measurable hit-rate/latency results.
*   Kafka events are published reliably through an outbox mechanism.
*   Consumers handle retries and duplicates safely.
*   Reservations expire and release inventory.
*   Failure scenarios have been tested.
*   Load tests produce reproducible numbers.
*   Logs/metrics/traces are available.
*   Integration tests use realistic dependencies.
*   GitHub Actions runs the core quality gates.
*   README explains architecture and trade-offs with diagrams and benchmark evidence.

## 32. What to Put in the GitHub README
```markdown
# High-Concurrency Inventory & Reservation Platform

## Problem
How do we safely process thousands of concurrent purchase requests
when inventory is limited?

## Architecture
[diagram]

## Engineering Challenges
- Preventing overselling
- Concurrent inventory updates
- Idempotent requests
- Database optimization
- Cache consistency
- Reliable event publication
- Retry and failure recovery
- Observability

## Concurrency Strategy
Pessimistic vs optimistic locking
[benchmark]

## Database Optimization
EXPLAIN ANALYZE before/after

## Caching
Redis strategy + hit ratio

## Reliability
Outbox + retries + DLQ

## Load Testing
1k / 2k / 5k concurrent scenarios
[graphs]

## Failure Testing
Kafka unavailable / Redis unavailable / worker crash

## How to Run
docker compose up ...

## Architecture Decisions
[ADR links]
```
Make the README evidence-driven. A recruiter should be able to understand the problem and your engineering decisions in a few minutes, while an engineer should be able to go deeper into the code, tests and benchmark notes.

## 33. Resume Entry
Use this as the eventual structure. Replace any placeholder metrics with numbers you actually measured.

**High-Concurrency Inventory & Reservation Platform | Java, Spring Boot, PostgreSQL, Redis, Kafka, Docker, AWS**
*   Engineered a concurrent inventory reservation system using PostgreSQL transactions and locking strategies to prevent inventory overselling under high-volume concurrent purchase requests.
*   Optimized database performance through indexing, query-plan analysis and connection-pool tuning, validating improvements with large-scale test datasets and load benchmarks.
*   Implemented Redis caching, API idempotency and Kafka-based asynchronous workflows with transactional outbox processing, retries and dead-letter handling for reliable event delivery.
*   Added distributed tracing, application metrics and load testing to identify latency bottlenecks and evaluate system behavior under sustained concurrent traffic.

Do not write invented throughput or latency numbers. The strongest version of the resume bullet is the one you can immediately defend in an interview.

## 34. Interview Topics and Questions

**Concurrency**
*   Why did you choose pessimistic vs optimistic locking?
*   What race condition did the naive implementation have?
*   What happens when 2,000 requests target one inventory row?
*   How do you prove that stock can never become negative?
*   What happens when the transaction fails halfway through?

**Database**
*   Why PostgreSQL?
*   How did you choose your indexes?
*   What does EXPLAIN ANALYZE tell you?
*   Why does composite index column order matter?
*   What is the difference between READ COMMITTED and SERIALIZABLE?
*   Why is increasing the DB connection pool not always good?

**Caching**
*   Why cache product reads but not use Redis as inventory truth?
*   How do you handle stale cache data?
*   What happens if Redis goes down?
*   How did you measure the cache improvement?

**Distributed systems**
*   Why Kafka instead of synchronous HTTP for notifications?
*   What if Kafka publish fails after the DB commit?
*   Explain the outbox pattern.
*   Can Kafka consumers receive duplicates? How do you handle that?
*   How do you handle poison messages?

**Reliability**
*   How does idempotency prevent duplicate orders?
*   How does reservation expiry work?
*   What happens if a worker crashes?
*   How does the system behave when Redis or Kafka is unavailable?

**Performance**
*   What was your P95 / P99 latency?
*   What was the bottleneck under load?
*   What optimization produced the biggest measurable improvement?
*   How does the system behave as traffic increases?

## 35. What This Project Proves About You
*   You can build beyond CRUD.
*   You understand database correctness, not just ORM syntax.
*   You understand concurrency and race conditions.
*   You can choose between consistency and performance deliberately.
*   You understand asynchronous processing and eventual consistency.
*   You know why idempotency matters in real APIs.
*   You can reason about failure recovery.
*   You measure performance instead of making unsupported scalability claims.
*   You can instrument and debug a distributed system.
*   You can explain architecture trade-offs to an interviewer.

This is the intended narrative: “I did not just build an app; I designed and tested the behavior of a system under contention and failure.”

## 36. What NOT to Over-Engineer
*   Do not create 15–20 microservices. Three core services plus focused workers are enough.
*   Do not start with Kubernetes. Docker Compose is enough while you learn the engineering core.
*   Do not deploy to AWS before local correctness and tests are solid.
*   Do not build a huge frontend. A simple product/reservation/order UI is enough.
*   Do not use Redis locks for everything. Database transactions/locking should protect database-owned invariants.
*   Do not claim “distributed system” without demonstrating a real distributed failure or consistency problem.
*   Do not put every technology on the resume unless you actually used and can explain it.

## 37. Final Recommended Stack

| Category | Final choice |
| :--- | :--- |
| Primary language | Java 21 or Java 25 LTS |
| Framework | Spring Boot |
| API | REST externally + gRPC internally |
| Database | PostgreSQL |
| DB access | Spring Data JPA + Spring JDBC/native SQL |
| Cache | Redis |
| Messaging | Apache Kafka |
| Tests | JUnit 5, Mockito, Testcontainers |
| Load testing | k6 |
| Observability | OpenTelemetry, Prometheus, Grafana |
| Logging | Structured logs + correlation IDs |
| Local infra | Docker Compose |
| Cloud | AWS |
| Infrastructure as Code | Terraform |
| CI/CD | GitHub Actions |
| Target duration | ~6 weeks recommended |

**Final recommendation:** build this project in Java + Spring Boot. Keep Go as a future dedicated learning project after this one. The language is only one part of the value; the standout signal comes from the concurrency, database, caching, messaging, idempotency, reliability, benchmarking and observability work you can explain and demonstrate.

**Portfolio principle:** measure what you claim. Any resume metric should come from a reproducible benchmark, test, or production observation you can explain.