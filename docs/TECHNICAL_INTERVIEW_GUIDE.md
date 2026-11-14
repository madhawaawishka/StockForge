# StockForge — Master Technical Interview Guide
### The Definitive Q&A, Architectural Defenses, Code Evidence & Benchmark Truths
*(Directly Aligned with Project Specification §41 and Production Implementation)*

> **Target Audience:** Candidates interviewing for Senior, Staff, or Principal Backend / Distributed Systems Engineer roles using **StockForge** as their capstone system.
>
> **Methodology:** Every question reflects the exact inquiries technical interviewers (architects, engineering directors, staff engineers) ask when reviewing this repository. Every answer is grounded in actual codebase files, PostgreSQL execution plans, Testcontainers suites, and empirical benchmarks.

---

## Complete Table of Contents
1. [Core Architecture & System Decomposition](#1-core-architecture--system-decomposition)
2. [Concurrency & Flash-Sale Database Mechanics](#2-concurrency--flash-sale-database-mechanics)
3. [Database Deep Dive: Indexing, Isolation & Schema Evolution](#3-database-deep-dive-indexing-isolation--schema-evolution)
4. [Caching Strategy & Redis Architecture](#4-caching-strategy--redis-architecture)
5. [Distributed Systems, Saga Orchestration & Consistency](#5-distributed-systems-saga-orchestration--consistency)
6. [Reliable Messaging: Outbox, Inbox & Kafka](#6-reliable-messaging-outbox-inbox--kafka)
7. [Reliability, Resilience & Fault Injection](#7-reliability-resilience--fault-injection)
8. [Performance Profiling, Virtual Threads & Benchmarks](#8-performance-profiling-virtual-threads--benchmarks)
9. [Design Quality, Testing Strategy & Clean Architecture](#9-design-quality-testing-strategy--clean-architecture)
10. [Observability, Distributed Tracing & SLO Alerts](#10-observability-distributed-tracing--slo-alerts)
11. [API Security & OWASP Top 10 Defenses](#11-api-security--owasp-top-10-defenses)
12. [Behavioral Incidents, Real Postmortems & 10× Scaling](#12-behavioral-incidents-real-postmortems--10-scaling)
13. [Key Benchmark Numbers Cheatsheet](#13-key-benchmark-numbers-cheatsheet)

---

## 1. Core Architecture & System Decomposition

### Q1.1: Walk me through the architecture. Why split this into 5 microservices instead of a modular monolith?
* **Interviewer's Focus:** Can you justify microservice boundaries with architectural rigor instead of hype?
* **Concise Answer:** StockForge separates concerns into 5 services—**API Gateway**, **Inventory Service**, **Order Service**, **Payment Service**, and **Notification Worker**. We decomposed by *independent scaling requirements*, *failure blast radiuses*, and *database contention isolation*.
* **The Technical Truth (Codebase Evidence):**
  * **API Gateway (`:8080`):** Stateless edge routing, JWT validation, Redis token-bucket rate limiting, Resilience4j bulkheads, and RFC 9457 error mapping. Runs on Java 21 Virtual Threads (`GATEWAY_VIRTUAL_THREADS=true`).
  * **Inventory Service (`:8081`, gRPC `:9091`):** Owns the high-contention write path. Uses a dedicated database schema (`inventory`), tuned with platform threads and a strict 10-connection pool to serialize on the hot product row.
  * **Order Service (`:8082`, gRPC `:9092`):** State machine orchestrator for the checkout saga. Owns the `ordering` schema and tracks order history.
  * **Payment Service (`:8083`):** Isolates the slow, flaky external Payment Service Provider (PSP) HTTP calls. Isolating it ensures external network pauses never tie up database connections in inventory or order services.
  * **Notification Worker (`:8084`):** Pure asynchronous consumer on Kafka; can lag or crash without impacting order placement.
  * *Code Reference:* [docs/architecture/README.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/architecture/README.md), [ADR-002](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-002-schema-per-service.md).
* **Trap / Follow-up Defense:**
  * *Interviewer:* "Isn't 5 microservices overkill for a single database instance?"
  * *Defense:* "In production, each service maps to its own physical database or RDS cluster. In this repository, we enforce logical isolation via **schema-per-service** (`inventory`, `ordering`, `payment`, `notification`) with separate PostgreSQL users and roles ([`01-roles-and-schemas.sql`](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/database/init/01-roles-and-schemas.sql)). Microservices are forbidden from joining across schemas; cross-domain data exchange happens strictly via Protobuf/gRPC contracts or versioned Kafka events. ArchUnit tests enforce this boundary in CI."

---

### Q1.2: Why communicate via synchronous gRPC between Gateway and Services, but asynchronous Kafka events for Checkout?
* **Interviewer's Focus:** Understanding synchronous request-reply vs. asynchronous event-driven workflows.
* **Concise Answer:** We use synchronous gRPC where immediate client feedback is mandatory (e.g., reserving scarce inventory or reading order status). We switch to asynchronous Kafka messaging once the order is placed because payment capture and fulfillment are multi-step, I/O-heavy distributed workflows that must survive process crashes.
* **The Technical Truth (Codebase Evidence):**
  * When reserving stock (`POST /api/v1/reservations`), the gateway calls `InventoryGrpcService.reserveStock()` over gRPC with a **2.0-second deadline**, protected by Resilience4j bulkheads and circuit breakers. The customer needs to know immediately (in < 200 ms) whether they got a unit.
  * When placing an order (`POST /api/v1/orders`), the gateway returns `202 Accepted` with a `Location: /api/v1/orders/{orderId}` header immediately after inserting the order in `PENDING_PAYMENT` state and appending an outbox message. The checkout saga then executes asynchronously over Kafka.
  * *Code Reference:* [PlaceOrderTransaction.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/PlaceOrderTransaction.java), [docs/architecture/README.md §4.1-4.2](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/architecture/README.md#L125-L208).

---

### Q1.3: What is Hexagonal Architecture (Ports and Adapters), and how do you prevent architectural erosion?
* **Interviewer's Focus:** Software design patterns, clean code, and automated architectural compliance.
* **Concise Answer:** Hexagonal architecture isolates the core domain logic from frameworks, databases, and network transports. The domain depends only on pure Java interfaces (ports). Adapters implement these ports. We prevent architectural decay using **ArchUnit** tests in our build pipeline.
* **The Technical Truth (Codebase Evidence):**
  * `domain`: Pure Java (models, value objects, exceptions, and port interfaces). Zero Spring or framework imports.
  * `application`: Use cases orchestrating domain entities and transaction boundaries.
  * `adapter.in`: Inbound adapters (gRPC controllers, REST controllers, Kafka message listeners, scheduled jobs).
  * `adapter.out`: Outbound adapters (JDBC repositories, Redis cache, Outbox publishers).
  * ArchUnit rules in `ArchitectureTest.java` enforce:
    ```java
    classes().that().resideInAPackage("..domain..")
        .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..");
    ```
  * *Code Reference:* [ADR-004](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-004-hexagonal-architecture.md).

---

## 2. Concurrency & Flash-Sale Database Mechanics

### Q2.1: What race condition did the naive read-then-write implementation have?
* **Interviewer's Focus:** Clear understanding of race conditions, lost updates, and why application-level checks fail.
* **Concise Answer:** The naive approach reads stock in Java (`if (stock >= requested)`), decrements in memory, and writes `UPDATE inventory SET available_quantity = :computed`. If two concurrent requests read available stock as `1`, both evaluate the `if` check as true, both write back `0`, and both succeed. This causes a **lost update** and **oversells physical inventory**.
* **The Technical Truth (Codebase Evidence):**
  * Implemented in `NaiveReservationStrategy.java` and proven by `StockReservationConcurrencyIT.naiveReadThenWriteOversells`:
    * When 50 concurrent requests target 5 physical units, the naive strategy accepts significantly more than 5 reservations.
    * Crucially, the internal equation `available + reserved + sold = total` remains mathematically balanced, so even standard CHECK constraints cannot detect the theft—the race happened in application memory before write-back!
  * *Code Reference:* [NaiveReservationStrategy.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/out/persistence/NaiveReservationStrategy.java), [StockReservationConcurrencyIT.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/test/java/com/stockforge/inventory/adapter/out/persistence/StockReservationConcurrencyIT.java).

---

### Q2.2: What happens inside PostgreSQL when 2,000–5,000 requests target one inventory row at the exact same millisecond?
* **Interviewer's Focus:** Relational engine internals: row-level tuple locking, MVCC, and transaction serialization.
* **Concise Answer:** Under PostgreSQL `READ COMMITTED`, every transaction attempting to update the row queues on that row's exclusive tuple lock. Transactions serialize one by one. Once the active transaction commits, the next waiting transaction wakes up, re-evaluates the `WHERE` predicate against the *latest committed* version of the row, and either executes or updates 0 rows.
* **The Technical Truth (Codebase Evidence):**
  * In `JdbcInventoryRepository.decrementAvailableAndIncrementReserved()`:
    ```sql
    UPDATE inventory
    SET available_quantity = available_quantity - :qty,
        reserved_quantity  = reserved_quantity  + :qty,
        version            = version + 1,
        updated_at         = :updatedAt
    WHERE product_id = :productId
      AND available_quantity >= :qty
    ```
  * **PostgreSQL EvalPlanQual (EPQ) Mechanism:**
    * When transaction $T_2$ attempts to update row $R$ currently locked by $T_1$, $T_2$ suspends on the lock.
    * When $T_1$ commits, $T_2$ wakes up. Under PostgreSQL's `EvalPlanQual` routine, it re-reads row $R$'s latest committed tuple and re-evaluates the `WHERE` condition (`available_quantity >= :qty`).
    * If units remain, $T_2$ applies its decrement and commits.
    * If units hit 0, the `WHERE` predicate evaluates to `false`. The statement returns `rows affected = 0`.
    * In Java, `rows == 1` returns `false`, prompting an immediate `StockReservationResult.InsufficientStock` which maps to HTTP `409 Conflict`. Zero overselling occurs.
  * *Code Reference:* [AtomicConditionalUpdateReservationStrategy.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/out/persistence/AtomicConditionalUpdateReservationStrategy.java), [ADR-006](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-006-inventory-reservation-concurrency-strategy.md).

---

### Q2.3: Why choose an Atomic Conditional UPDATE over Pessimistic (`SELECT FOR UPDATE`) or Optimistic (`@Version`) Locking?
* **Interviewer's Focus:** Practical benchmarking experience. Did you benchmark alternative concurrency paradigms?
* **Concise Answer:** All three can prevent overselling, but **only the atomic conditional update does so without error**. Both pessimistic and optimistic locking **starved the connection pool**, causing severe 503 outages. Furthermore, optimistic locking **undersold** (turned buyers away while 35–39 units remained in stock).
* **The Technical Truth (Empirical Benchmark Evidence):**
  * *Benchmark Results ([docs/benchmarks/concurrency-strategies.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/concurrency-strategies.md)):*
    * **Atomic Conditional UPDATE:** Sold exactly **500/500**, 4,500 clean 409s, **0 unexpected errors**, 0 connection pool wait queue, P99 = 230–254 ms.
    * **Pessimistic Locking (`SELECT ... FOR UPDATE`):** Sold 500/500, but suffered **up to 4,270 unexpected 503 errors**. 92 requests waited for database connections. Holding the row lock across multiple SQL round-trips caused connection hold times to skyrocket.
    * **Optimistic Locking (`WHERE version = :v` with 5 retries):** **Undersold! Only 461 units sold** out of 500. Up to 191 requests waiting for connections, 684 `CannotCreateTransactionException` failures, and circuit breakers tripped. On a single hot SKU, almost 100% of concurrent updates conflict, forcing sleep retries inside open transactions that exhaust connection pools.
  * *Code Reference:* [docs/benchmarks/concurrency-strategies.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/concurrency-strategies.md).

---

### Q2.4: What is "Write Skew" in purchase limits, and how did you prevent it under READ COMMITTED?
* **Interviewer's Focus:** Deep concurrency anomalies. Can you detect race conditions that simple unique constraints or single-row locks don't catch?
* **Concise Answer:** Write skew occurs when concurrent transactions read intersecting data, determine an invariant is satisfied, and perform inserts on *disjoint* rows, violating the global invariant. If we enforce "maximum 2 units per user" using `SELECT SUM(qty) ...` followed by `INSERT`, two concurrent requests see 1 unit and both insert, resulting in 3 units. We solved this with an **atomic counter row** and a PostgreSQL CHECK constraint.
* **The Technical Truth (Codebase Evidence):**
  * In `JdbcUserPurchaseLimitRepository.tryAcquire()`:
    ```sql
    INSERT INTO user_product_limits (user_id, product_id, active_quantity, limit_quantity, updated_at)
    SELECT :userId, :productId, :qty, :maxLimit, :updatedAt
    WHERE :qty <= :maxLimit
    ON CONFLICT (user_id, product_id) DO UPDATE
    SET active_quantity = user_product_limits.active_quantity + :qty,
        limit_quantity  = :maxLimit,
        updated_at      = :updatedAt
    WHERE user_product_limits.active_quantity + :qty <= :maxLimit
    ```
  * In PostgreSQL, `ON CONFLICT (user_id, product_id) DO UPDATE` acquires an exclusive row-level lock on the `(user_id, product_id)` row. The second transaction blocks until the first completes, then evaluates the `WHERE` condition. If active quantity would exceed limit, 0 rows update.
  * Verified in integration tests: `PurchaseLimitWriteSkewIT.java` reliably demonstrates the vulnerability in naive read-then-write and proves the atomic counter row eliminates it.
  * *Code Reference:* [PurchaseLimitWriteSkewIT.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/test/java/com/stockforge/inventory/PurchaseLimitWriteSkewIT.java).

---

### Q2.5: How do you prove that stock can never become negative or oversold?
* **Interviewer's Focus:** Defense-in-depth, data integrity, and mathematical invariants.
* **Concise Answer:** We enforce integrity at three independent layers: **Domain Model Validation**, **Database CHECK Constraints**, and **Continuous Asynchronous Reconciliation**.
* **The Technical Truth (Codebase Evidence):**
  1. **Domain Layer:** In `Inventory.java`, domain operations check boundaries before returning mutated records.
  2. **Database Schema Constraints:**
     * `CONSTRAINT inventory_stock_conservation CHECK (available_quantity + reserved_quantity + sold_quantity = total_quantity)`
     * `CONSTRAINT inventory_available_non_negative CHECK (available_quantity >= 0)`
     * `CONSTRAINT user_product_limits_non_negative CHECK (active_quantity >= 0)`
     * Even if raw SQL is executed by a rogue script, PostgreSQL aborts the transaction before committing corrupt state.
  3. **Continuous Reconciliation Job:** `JdbcInventoryReconciliation.java` runs every minute using a `REPEATABLE READ` snapshot to verify:
     $$\text{reserved\_quantity} = \sum(\text{pending reservations})$$
     If a discrepancy is detected, it logs an alert and triggers Prometheus metric `stockforge_invariant_violations_total`.
  * *Code Reference:* [V1__create_products_and_inventory.sql](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/resources/db/migration/V1__create_products_and_inventory.sql#L37), [check-invariants.sql](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/scripts/check-invariants.sql).

---

### Q2.6: What happens when a reservation transaction fails halfway through?
* **Interviewer's Focus:** Atomicity of transactions and idempotency failure recovery.
* **Concise Answer:** The entire reservation operation—idempotency key acquisition, purchase limit check, stock decrement, reservation row insertion, and outbox event publishing—occurs within a single database transaction. If any step fails, the entire transaction rolls back cleanly, leaving zero side-effects.
* **The Technical Truth (Codebase Evidence):**
  * Annotated with `@Transactional` in `ReserveStockUseCase.execute()`.
  * If a network timeout, constraint violation, or crash occurs before `COMMIT`:
    * The stock decrement is undone.
    * The purchase limit counter is reverted.
    * The reservation row is discarded.
    * The outbox event is not persisted.
    * The idempotency key row is rolled back, allowing the client to safely retry the request with the exact same `Idempotency-Key`.
  * *Code Reference:* [ReserveStockUseCase.java#L84-L120](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/application/ReserveStockUseCase.java#L84-L120).

---

### Q2.7: What is the lock acquisition order in a reservation transaction, and how does it prevent deadlocks?
* **Interviewer's Focus:** Deadlock avoidance strategies in relational databases.
* **Concise Answer:** Deadlocks occur when concurrent transactions acquire multiple resources in different orders. In StockForge, all write workflows—reservations, cancellations, and expiries—acquire locks in a **strict, global sequence**:
  1. **Idempotency Key Row** (`idempotency_keys`)
  2. **User Purchase Limit Row** (`user_product_limits`)
  3. **Product Inventory Row** (`inventory`)
* **The Technical Truth:**
  * Because every flow acquires row locks in this identical sequence, a cycle in the PostgreSQL lock wait-for graph is mathematically impossible. Deadlocks between reservations and background expiries/cancellations are completely eliminated.
  * *Code Reference:* [ReserveStockUseCase.java#L44-L46](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/application/ReserveStockUseCase.java#L44-L46).

---

## 3. Database Deep Dive: Indexing, Isolation & Schema Evolution

### Q3.1: Why PostgreSQL over NoSQL (e.g. MongoDB, Cassandra, DynamoDB)?
* **Interviewer's Focus:** Data model selection, transactional semantics, and architectural trade-offs.
* **Concise Answer:** Flash sales demand rock-solid ACID transactions across multiple entities (stock levels, user limits, reservations, and outbox messages) on a single node. NoSQL databases offer eventual consistency and lack multi-table constraints. DynamoDB conditional writes fail under contention with `TransactionCanceledException`, forcing application retry storms identical to optimistic locking.
* **The Technical Truth:**
  * PostgreSQL gives us:
    1. Multi-statement ACID transactions within a schema.
    2. Atomic conditional updates with `EvalPlanQual` row-level queuing.
    3. CHECK constraints (`available + reserved + sold = total`) preventing invalid states at the engine level.
    4. Advanced concurrency features like `FOR UPDATE SKIP LOCKED` and advisory locks (`pg_try_advisory_xact_lock`).
    5. Highly efficient partial indexing.

---

### Q3.2: Why READ COMMITTED instead of SERIALIZABLE isolation?
* **Interviewer's Focus:** Deep database isolation level theory and performance implications.
* **Concise Answer:** Under PostgreSQL `SERIALIZABLE` isolation (Serializable Snapshot Isolation - SSI), concurrent transactions that read and update the same hot inventory row generate read-write conflicts (rw-antidependencies). PostgreSQL aborts these transactions with `ERROR: 40001: could not serialize access`. Under a flash sale of 5,000 requests, almost 100% of transactions would abort, destroying throughput.
* **The Technical Truth:**
  * `READ COMMITTED` allows transactions to queue on row locks rather than aborting. Combined with our atomic conditional `UPDATE`, waiting transactions re-read the committed tuple via `EvalPlanQual` and process sequentially without serialization failures.
  * *Code Reference:* [ADR-006](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-006-inventory-reservation-concurrency-strategy.md).

---

### Q3.3: How did you design database indexes, and why does composite index column order matter?
* **Interviewer's Focus:** Query planner mechanics, B-Tree index structure, and pagination performance.
* **Concise Answer:** Indexes are designed from our query access patterns, not table schemas. Composite indexes place **equality columns first**, followed by **range/sort columns**. In `idx_orders_user_history (user_id, created_at DESC, id)`, placing `user_id` first allows the B-Tree to immediately isolate that user's partition before walking timestamps.
* **The Technical Truth (Codebase Evidence):**
  * In `orders`:
    ```sql
    CREATE INDEX idx_orders_user_history ON orders (user_id, created_at DESC, id);
    ```
  * Query:
    ```sql
    WHERE user_id = :userId AND (created_at, id) < (:cursorCreatedAt, :cursorId)
    ORDER BY created_at DESC, id LIMIT 20;
    ```
  * If column order were reversed `(created_at, user_id)`, PostgreSQL would scan the entire global timestamp index and filter each entry for `user_id`, rendering pagination slow on large datasets.
  * Keyset pagination avoids `OFFSET`: page 1,000 costs the exact same as page 1 (**0.04 ms**).
  * *Code Reference:* [docs/benchmarks/query-plans.md §3-4](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/query-plans.md#L67-L79).

---

### Q3.4: How did Partial Indexes reduce background query latency from 328 ms to 0.4 ms?
* **Interviewer's Focus:** Advanced PostgreSQL indexing strategies on high-velocity transactional tables.
* **Concise Answer:** Polling workers query a tiny, moving subset of records (e.g. pending outbox rows or expired reservations) inside million-row tables. Normal indexes index all 1,000,000 rows. A **partial index** with a `WHERE` clause indexes only the active rows, keeping index size in memory and search execution times sub-millisecond.
* **The Technical Truth (Benchmark Evidence):**
  * *Measured on 1.15M Outbox Rows and 562k Reservation Rows ([docs/benchmarks/query-plans.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/query-plans.md)):*
    1. **Outbox Relay Polling:**
       ```sql
       CREATE INDEX outbox_events_pending_idx ON outbox_events (seq) WHERE status = 'PENDING';
       ```
       * Execution time **with partial index: 0.38 ms** (69 buffer hits).
       * Execution time **without partial index: 328.2 ms** (40,801 buffer hits, full table sequential scan).
    2. **Reservation Expiry Worker:**
       ```sql
       CREATE INDEX idx_reservations_pending_expires_at ON reservations (expires_at) WHERE status = 'PENDING';
       ```
       * Execution time **with partial index: 0.80 ms** (123 buffer hits).
       * Execution time **without partial index: 55.3 ms** (15,185 buffer hits).
  * Without partial indexes, continuous polling would saturate database CPU with sequential scans across historical records.

---

### Q3.5: Why is there NO index on `available_quantity` in the `inventory` table?
* **Interviewer's Focus:** Understanding write penalties of indexes and PostgreSQL HOT (Heap-Only Tuple) optimization.
* **Concise Answer:** `inventory` is the most frequently updated row in the entire database during a flash sale. Adding an index on `available_quantity` would force PostgreSQL to update the B-Tree index on every single reservation, destroying **HOT (Heap-Only Tuple)** updates and dramatically increasing write I/O.
* **The Technical Truth (Codebase Evidence):**
  * The reservation query locates the row via Primary Key (`product_id = :id`):
    ```sql
    WHERE product_id = :productId AND available_quantity >= :qty
    ```
  * `product_id` is already indexed by the Primary Key. The check `available_quantity >= :qty` acts as a cheap row filter in CPU memory.
  * With no secondary indexes on the updated columns, PostgreSQL can update the tuple inside the same 8KB data page (HOT update), avoiding any index writes.
  * *Code Reference:* [V1__create_products_and_inventory.sql#L41-L43](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/resources/db/migration/V1__create_products_and_inventory.sql#L41-L43).

---

### Q3.6: Why did you cap database connection pools to 10 connections per service?
* **Interviewer's Focus:** Connection pool sizing theory, Little's Law, and database process contention.
* **Concise Answer:** PostgreSQL uses a process-per-connection architecture. Increasing pool size does not create throughput; it adds CPU context switching and disk contention. Furthermore, under hot-row contention, only one transaction can hold the row lock at a time. Increasing connections merely creates a huge queue of processes parked waiting on the exact same row lock, exhausting memory.
* **The Technical Truth (Benchmark Evidence):**
  * **Little's Law:** $\text{Throughput} = \frac{\text{Concurrency}}{\text{Hold Time}}$.
  * With atomic conditional updates, each reservation holds its database connection for only $\approx 10\text{ ms}$.
  * A 10-connection pool provides: $\frac{10}{0.010\text{ s}} = 1,000\text{ req/s}$ capacity.
  * Under load testing at 500 req/s, the atomic update experienced **0 waiting requests** for connections.
  * Conversely, when testing pessimistic locking with the same 10-connection pool, hold times ballooned and **92 requests were blocked in HikariCP's wait queue**. A bigger pool would simply have multiplied blocked processes in PostgreSQL.
  * *Code Reference:* [docs/benchmarks/connection-pool-sizing.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/connection-pool-sizing.md).

---

### Q3.7: How do you change a database schema without downtime?
* **Interviewer's Focus:** Continuous deployment, zero-downtime database migrations, and backward compatibility.
* **Concise Answer:** We apply the **Expand/Contract (Parallel Run) Pattern** using Flyway migrations. You never alter or rename columns destructively in a single release. You add nullable columns/new tables (expand), deploy code that writes both versions, backfill old rows, switch reads, and finally remove the old structure (contract).
* **The Technical Truth (Codebase Evidence):**
  * Demonstrated in `V3__saga_state_messaging_and_audit.sql` in `order-service`:
    1. **Expand:** Added `saga_step` column as nullable:
       ```sql
       ALTER TABLE orders ADD COLUMN saga_step TEXT;
       ```
    2. **Backfill:** Backfilled legacy orders based on current status:
       ```sql
       UPDATE orders SET saga_step = CASE ... END;
       ```
    3. **Contract:** Applied constraints only after data was fully consistent:
       ```sql
       ALTER TABLE orders ALTER COLUMN saga_step SET NOT NULL;
       ALTER TABLE orders ADD CONSTRAINT orders_saga_step_valid CHECK (...);
       ```
  * *Code Reference:* [V3__saga_state_messaging_and_audit.sql](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/resources/db/migration/V3__saga_state_messaging_and_audit.sql#L43-L60).

---

### Q3.8: Why choose UUIDv7 identifiers instead of UUIDv4 or BIGSERIAL?
* **Interviewer's Focus:** Distributed ID generation, index fragmentation, and database security.
* **Concise Answer:** Auto-incrementing integers (BIGSERIAL) leak business volume to competitors and enable enumeration attacks (BOLA). Random UUIDv4 causes massive B-Tree index fragmentation and cache thrashing. **UUIDv7** embeds a 48-bit millisecond timestamp in the most significant bits, providing time-ordered monotonic B-Tree inserts alongside global client-side generation.
* **The Technical Truth (Codebase Evidence):**
  * Implemented in `UuidV7Generator.java`:
    * Bits 0–47: Unix epoch timestamp in milliseconds.
    * Bits 48–127: Version 7 tag and cryptographically secure random bits.
  * B-Tree inserts append sequentially to the rightmost leaf page, eliminating costly page splits and preserving high buffer cache hit ratios.
  * *Code Reference:* [ADR-005](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-005-uuidv7-identifiers.md), [UuidV7Generator.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/out/id/UuidV7Generator.java).

---

## 4. Caching Strategy & Redis Architecture

### Q4.1: Why cache product catalog reads in Redis but never use Redis as the inventory truth?
* **Interviewer's Focus:** Understanding data durability boundaries and separation of read vs. write workloads.
* **Concise Answer:** Catalog details (names, descriptions, prices) are read-heavy and rarely change, making them perfect for Redis caching. Real-time inventory during a flash sale requires relational CHECK constraints, row locking, and durable persistence. Redis replication is asynchronous; an unexpected failover would lose increments and cause catastrophic physical overselling.
* **The Technical Truth (Codebase Evidence):**
  * `GET /api/v1/products/{id}` reads from Redis via `RedisProductCache.java`. Stock returned from cache is explicitly labeled `approximate`.
  * `POST /api/v1/reservations` **completely bypasses Redis** and checks PostgreSQL atomically.
  * *Code Reference:* [RedisProductCache.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/out/cache/RedisProductCache.java).

---

### Q4.2: How do you handle stale cache data and invalidations?
* **Interviewer's Focus:** Cache invalidation timing, transaction boundaries, and HTTP conditional requests.
* **Concise Answer:** We use **Cache-Aside with Post-Commit Invalidation** and **HTTP ETags**. Redis entries have a short TTL (10 minutes). Whenever a product is updated, Redis is evicted strictly **after** database transaction commit. Clients use `If-None-Match` headers to receive `304 Not Modified` on unchanged products.
* **The Technical Truth (Codebase Evidence):**
  * Invalidation is registered using Spring's `TransactionSynchronization.afterCommit()`:
    ```java
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            cache.evict(productId);
        }
    });
    ```
    If invalidation occurred *before* commit and the transaction rolled back, the cache would be evicted pointlessly or left inconsistent with dirty reads.
  * *Code Reference:* [RedisProductCache.java#L110-L135](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/out/cache/RedisProductCache.java#L110-L135).

---

### Q4.3: What is a Cache Stampede (Thundering Herd), and how did you prevent it at sale start?
* **Interviewer's Focus:** High-concurrency cache miss mitigation.
* **Concise Answer:** When a popular product's cache entry expires right as a flash sale starts, thousands of concurrent requests miss cache simultaneously and bombard the database with duplicate queries. We prevent this using **Single-Flight Cache Miss Resolution** in `ProductCatalogQueries.java`.
* **The Technical Truth (Codebase Evidence):**
  * When a cache miss occurs, threads check a local concurrent map of in-flight `CompletableFuture<Product>`.
  * The first thread creates the loader future to fetch from PostgreSQL.
  * Subsequent concurrent threads attach to the existing future instead of querying the database.
  * Once the single database query completes, all waiting threads receive the result, and Redis is populated.
  * *Code Reference:* `ProductCatalogQueriesCacheTest.java`.

---

### Q4.4: What happens if Redis crashes?
* **Interviewer's Focus:** Graceful degradation and circuit breaker behavior.
* **Concise Answer:** Product reads fall back directly to PostgreSQL through a Resilience4j circuit breaker. Rate limiting in the gateway **fails open** with a high-priority Prometheus alert, allowing legitimate sales to proceed.
* **The Technical Truth (Codebase Evidence):**
  * Toxiproxy fault-injection test `InventoryChaosIT.redisOutageFallsBackToTheDatabase` verifies that when Redis TCP port is cut, product endpoints continue serving responses directly from PostgreSQL with zero 500 errors.
  * *Code Reference:* [ADR-010](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-010-rate-limiter-fails-open.md).

---

## 5. Distributed Systems, Saga Orchestration & Consistency

### Q5.1: Why an Orchestrated Saga over a Choreographed Saga for Checkout?
* **Interviewer's Focus:** Distributed workflow patterns, state management, and operational clarity.
* **Concise Answer:** Choreography leads to fragmented state across services, "pinball" event cascades, and difficult timeout handling. An **orchestrated saga** centralizes the state machine in `order-service`, provides a clear single source of truth for order status, and simplifies compensation rollbacks.
* **The Technical Truth (Codebase Evidence):**
  * `CheckoutSaga.java` in `order-service` acts as the orchestrator.
  * The order table contains two coordinated columns: `status` (`OrderStatus`) and `saga_step` (`SagaStep`).
  * Valid state transitions are enforced at the database level:
    ```sql
    CONSTRAINT orders_saga_step_matches_status CHECK (
        (status = 'PENDING_PAYMENT' AND saga_step IN ('PAYMENT_REQUESTED', 'CONFIRMING_RESERVATION'))
        OR (status = 'REFUND_PENDING' AND saga_step = 'REFUNDING')
        OR (status IN ('CONFIRMED', 'CANCELLED', 'REFUNDED') AND saga_step = 'COMPLETED')
    )
    ```
  * Every transition records an entry in `order_status_history` for full auditability.
  * *Code Reference:* [CheckoutSaga.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/CheckoutSaga.java), [ADR-007](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-007-orchestrated-checkout-saga.md).

---

### Q5.2: What happens if payment succeeds after the reservation has already expired?
* **Interviewer's Focus:** Handling distributed race conditions and consistency anomalies in sagas.
* **Concise Answer:** Inventory is the sole authority on stock. It will **never revive an expired reservation**. If `ConfirmReservation` arrives for an expired reservation, Inventory rejects the confirmation. The Order Service receives `ReservationConfirmationRejected`, transitions the order to `REFUND_PENDING`, and triggers a payment refund.
* **The Technical Truth (Codebase Evidence):**
  * In `ReservationTransitions.confirm()`:
    * If status is not `PENDING` (e.g., already `EXPIRED` by background cleanup), the update returns 0 rows.
    * Inventory Service publishes `ReservationConfirmationRejected`.
  * Order Service receives this event in `CheckoutSaga.onReservationConfirmationRejected()`:
    * Updates order status to `REFUND_PENDING` and saga step to `REFUNDING`.
    * Writes `RefundPayment` command to outbox destined for `payment-commands`.
    * When Payment Service confirms refund, order transitions to `REFUNDED`.
  * *Result:* The customer is never charged for a seat they didn't get, and the system never oversells.
  * *Code Reference:* [ADR-009](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-009-payment-after-reservation-expiry.md), [CheckoutSaga.java#L160](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/CheckoutSaga.java#L160).

---

### Q5.3: What happens if a compensating action fails (e.g. refund service is unreachable)?
* **Interviewer's Focus:** Error handling in sagas and avoiding stuck intermediate states.
* **Concise Answer:** Compensations are issued as durable commands through the Transactional Outbox. If the refund call fails, the messaging layer retries with backoff. If exhausted, it routes to a Dead-Letter Topic (`payment-commands.DLT`). The order remains in a visible `REFUND_PENDING` state until resolved.
* **The Technical Truth (Codebase Evidence):**
  * Compensations are never "fire-and-forget". They are tracked in `orders.saga_step = 'REFUNDING'`.
  * Alerts fire when orders remain in `REFUND_PENDING` past an SLO threshold (`RUNBOOK-008`).
  * Once the payment provider recovers, operators replay the command via the dead-letter replay endpoint (`POST /api/v1/admin/dead-letters/replay`).
  * *Code Reference:* [ReplayDeadLettersUseCase.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/ReplayDeadLettersUseCase.java).

---

### Q5.4: How do you choose Kafka partition keys, and what ordering do you guarantee?
* **Interviewer's Focus:** Kafka topic architecture, partitioning strategies, and head-of-line blocking.
* **Concise Answer:** Partition keys are always set to the **Aggregate ID** (e.g. `orderId` or `reservationId`). We guarantee strict total ordering *per aggregate*, which is all distributed sagas require, while allowing full parallel consumption across different orders.
* **The Technical Truth (Codebase Evidence):**
  * In `OutboxRelay.send()`:
    ```java
    ProducerRecord<String, String> record =
        new ProducerRecord<>(event.topic(), event.aggregateId(), event.envelopeJson());
    ```
  * By keying on `aggregateId`, all events for Order `A` (`OrderCreated`, `PaymentSucceeded`, `OrderConfirmed`) land on the exact same Kafka partition and are consumed in sequence.
  * Events for Order `B` land on different partitions and process independently without head-of-line blocking.
  * *Code Reference:* [ADR-008](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-008-outbox-inbox-and-event-ordering.md).

---

### Q5.5: How is event schema evolution handled without a Schema Registry?
* **Interviewer's Focus:** Cross-service contract compatibility, forward/backward compatibility, and deployment ordering.
* **Concise Answer:** We use a **standard versioned envelope (`EventEnvelope`)**, enforce **additive-only changes**, and validate backwards compatibility using **checked-in golden contract tests** (`MessageContractTest`).
* **The Technical Truth (Codebase Evidence):**
  * Envelope structure: `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `payload`, `occurredAt`.
  * Rules enforced:
    1. Only additive changes (new optional fields) within a `schemaVersion`.
    2. Consumers ignore unknown JSON properties (`DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` on event consumer codecs).
    3. Breaking changes introduce a new `schemaVersion` published alongside the old one.
    4. `MessageContractTest` validates 56 JSON golden files on every build to guarantee serialization compatibility.
  * *Code Reference:* [ADR-012](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-012-event-schema-evolution.md).

---

## 6. Reliable Messaging: Outbox, Inbox & Kafka

### Q6.1: What is the Dual-Write Problem, and how does the Transactional Outbox solve it?
* **Interviewer's Focus:** Distributed data consistency without 2PC (Two-Phase Commit).
* **Concise Answer:** Dual-write occurs when a service must update a database and publish an event to Kafka. If the database commits but Kafka is down, the event is lost. If Kafka publishes but the database rolls back, you publish a phantom event. The **Transactional Outbox** writes the event directly into an `outbox_events` database table inside the exact same local database transaction as the domain state change, guaranteeing atomic persistence.
* **The Technical Truth (Codebase Evidence):**
  * Since both the business mutation (e.g. `orders` table) and the outbound event (`outbox_events` table) are committed in the same local ACID transaction, it is impossible for one to persist without the other.
  * A separate background worker (`OutboxRelay.java`) polls the `outbox_events` table and publishes them to Kafka with `acks=all`.
  * *Code Reference:* [PlaceOrderTransaction.java#L66-L92](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/PlaceOrderTransaction.java#L66-L92), [OutboxRelay.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/libs/messaging/src/main/java/com/stockforge/messaging/OutboxRelay.java).

---

### Q6.2: Walk me through `OutboxRelay.java`. How do you guarantee per-aggregate ordering and prevent multi-instance race conditions?
* **Interviewer's Focus:** Concurrency control in polling workers, database locking, and event sequencing.
* **Concise Answer:** `OutboxRelay` uses a PostgreSQL **transaction-scoped advisory lock** to ensure only one relay instance runs per service schema. It polls pending events ordered by `seq` using `FOR UPDATE SKIP LOCKED`, dispatches them asynchronously to Kafka in parallel, and marks rows `PUBLISHED` strictly up to the first failed send.
* **The Technical Truth (Codebase Evidence):**
  1. **Distributed Singleton Lock:**
     ```sql
     SELECT pg_try_advisory_xact_lock(hashtext('stockforge.outbox-relay.' || current_schema()))
     ```
     Scoped to the transaction: if the relay pod crashes, PostgreSQL automatically releases the lock immediately.
  2. **Batch Query:**
     ```sql
     SELECT id, topic, aggregate_id, event_type, payload, headers
     FROM outbox_events
     WHERE status = 'PENDING'
     ORDER BY seq
     LIMIT :limit
     FOR UPDATE SKIP LOCKED
     ```
  3. **Strict In-Order Publishing:** Messages are sent to Kafka in parallel using `CompletableFuture`. However, when inspecting results:
     ```java
     for (int i = 0; i < batch.size(); i++) {
         try {
             sends.get(i).get(deadline, TimeUnit.NANOSECONDS);
             acknowledged.add(event.id());
         } catch (Exception ex) {
             recordFailure(event, ex);
             break; // STOP marking published!
         }
     }
     ```
     If event #3 fails, events #4 and #5 are **not** marked `PUBLISHED`. They stay `PENDING` and will be retried in sequence, preventing later events from overtaking earlier events for the same aggregate.
  4. **Accurate Lag Measurement:**
     When updating status to `PUBLISHED`, the SQL calculates lag using `clock_timestamp()` instead of `now()`:
     ```sql
     UPDATE outbox_events
     SET status = 'PUBLISHED', published_at = clock_timestamp()
     WHERE id IN (:ids)
     RETURNING EXTRACT(EPOCH FROM published_at - created_at)::float8 AS lag
     ```
     `now()` returns the transaction start time; `clock_timestamp()` returns the true wall-clock time after Kafka acknowledged the write.
  * *Code Reference:* [OutboxRelay.java#L76-L150](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/libs/messaging/src/main/java/com/stockforge/messaging/OutboxRelay.java#L76-L150).

---

### Q6.3: How do consumers ensure Exactly-Once semantics? What was the bug in POSTMORTEM-004?
* **Interviewer's Focus:** Idempotent consumer design and real debugging experience.
* **Concise Answer:** Kafka provides at-least-once delivery. We achieve an **exactly-once effect** by combining at-least-once transport with an **Idempotent Inbox** table (`processed_events`). In POSTMORTEM-004, an earlier bug deduplicated events by Kafka partition key (Order ID) instead of Event UUID, which caused refund commands for an order to be silently dropped as duplicates of the payment command!
* **The Technical Truth (Codebase Evidence):**
  * Table: `processed_events (consumer_group TEXT, event_id UUID, processed_at TIMESTAMPTZ, PRIMARY KEY (consumer_group, event_id))`
  * In `InboxConsumer.java`:
    Inside the consumer's local transaction, it attempts to insert `(consumerGroup, eventId)` into `processed_events`. If a unique constraint violation occurs, the message is recognized as a duplicate and skipped cleanly.
  * **POSTMORTEM-004 Analysis:**
    * In early development, deduplication was mistakenly keyed on `aggregate_id` (Order ID).
    * When Order Service sent `RequestPayment` (Order ID: `123`), Payment Service processed it.
    * When Order Service later sent `RefundPayment` (Order ID: `123`), the consumer discarded it because Order `123` was already marked processed.
    * *Fix:* Key deduplication strictly on `(consumer_group, event_id)`.
  * *Code Reference:* [POSTMORTEM-004](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/postmortems/POSTMORTEM-004-refunds-discarded-as-duplicates.md), [V3__saga_state_messaging_and_audit.sql](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/resources/db/migration/V3__saga_state_messaging_and_audit.sql#L32-L37).

---

### Q6.4: How do you handle Poison Pill messages and Kafka consumer retries?
* **Interviewer's Focus:** Dead letter strategies, error classification, and operational recovery.
* **Concise Answer:** Transient errors trigger up to 4 retries with exponential backoff. Non-retryable errors (e.g. malformed JSON) skip retries immediately and route directly to a Dead-Letter Topic (`<topic>.DLT`) with exception headers. Operators can replay dead letters using an authenticated admin REST endpoint.
* **The Technical Truth (Codebase Evidence):**
  * Implemented in `libs/messaging` via Spring Kafka's `DefaultErrorHandler`.
  * Backoff: 100 ms initial, $2\times$ multiplier, max 4 attempts.
  * Destination topic: String concatenation `<original_topic>.DLT`.
  * Message headers appended: `x-original-topic`, `x-exception-message`, `x-exception-stacktrace`.
  * Metric: `stockforge_consumer_dead_letters_total` alerts on Prometheus if > 0.
  * Replay: `ReplayDeadLettersUseCase.java` reads messages from DLT and republishes to original topic after defect resolution.
  * *Code Reference:* [ADR-014](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-014-consumer-retries-and-dead-letters.md), [ReplayDeadLettersUseCase.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/order-service/src/main/java/com/stockforge/order/application/ReplayDeadLettersUseCase.java).

---

## 7. Reliability, Resilience & Fault Injection

### Q7.1: What is a Retry Storm, and how do timeouts, circuit breakers, and bulkheads prevent it?
* **Interviewer's Focus:** Cascading failures in distributed systems and load-shedding techniques.
* **Concise Answer:** A retry storm happens when a slow downstream causes clients to time out and retry without backoff. Each retry adds more load to an already struggling service, pushing it into total collapse. We prevent it via **strict deadlines**, **client bulkheads**, **circuit breakers**, and **exponential backoff with jitter**.
* **The Technical Truth (Codebase Evidence):**
  * **Strict Deadlines:** Gateway-to-service gRPC deadline is 2.0 seconds. Database statement timeout is 5.0 s; lock timeout is 2.0 s.
  * **Bulkheads:** Gateway restricts concurrent in-flight calls to Inventory (max 200) and Order (max 100). Excess requests are shed immediately with HTTP `503` and `Retry-After: 5` rather than queueing.
  * **Circuit Breakers:** Resilience4j trips open if error rate exceeds 50% over a 100-call window, immediately short-circuiting downstream calls.
  * **Business Separation:** `409 Insufficient Stock` and `422 Limit Exceeded` are filtered out so they never trigger circuit breakers.
  * *Code Reference:* [security.md §API4](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/security.md#L45-L57).

---

### Q7.2: How does reservation expiry work, and what happens if the worker crashes?
* **Interviewer's Focus:** Background job design, lock management, and crash recovery.
* **Concise Answer:** Expired reservations are cleaned up by a scheduled background worker executing every second. It uses `FOR UPDATE SKIP LOCKED` on the partial index `idx_reservations_pending_expires_at` in batches of 200. If the worker crashes, its database transaction rolls back, releasing locks immediately.
* **The Technical Truth (Codebase Evidence):**
  * Query:
    ```sql
    SELECT id, product_id, user_id, quantity
    FROM reservations
    WHERE status = 'PENDING' AND expires_at < now()
    ORDER BY expires_at
    LIMIT 200
    FOR UPDATE SKIP LOCKED
    ```
  * In `ReservationTransitions.expire()`:
    * Updates reservation status to `EXPIRED`.
    * Returns units to available inventory (`available_quantity = available_quantity + qty`).
    * Decrements user's purchase limit counter.
    * Publishes `ReservationExpired` to the outbox.
  * The expiry worker drains $\approx 700$ expired reservations per second.
  * *Code Reference:* [V2__create_reservations_and_idempotency.sql#L23-L26](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/resources/db/migration/V2__create_reservations_and_idempotency.sql#L23-L26).

---

### Q7.3: How did you test fault tolerance, and what did Toxiproxy uncover in POSTMORTEM-006?
* **Interviewer's Focus:** Chaos engineering and automated failure mode testing.
* **Concise Answer:** We wrote automated chaos suites using **Testcontainers and Toxiproxy** (`InventoryChaosIT.java`) to inject network latency, TCP resets, and packet drops. In POSTMORTEM-006, injecting a database partition revealed that socket timeouts were causing the service to return generic `500 Internal Server Error` instead of `503 Service Unavailable`, preventing clients from knowing they could retry.
* **The Technical Truth (Codebase Evidence):**
  * When a database network partition occurs, JDBC queries block until socket timeout.
  * The Spring application was throwing `CannotCreateTransactionException` wrapped in a generic unhandled exception, causing the gateway to emit HTTP 500.
  * Under RFC 9457 standards, downstream dependency unavailability must be communicated as **503 Service Unavailable** with a `Retry-After: 5` header.
  * *Fix:* Created `DependencyFailures.java` and registered global exception handlers mapping JDBC connection timeouts, socket timeouts, and Hikari pool starvation directly to gRPC `UNAVAILABLE` and HTTP 503.
  * *Code Reference:* [POSTMORTEM-006](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/postmortems/POSTMORTEM-006-database-partition-answered-500.md), [DependencyFailures.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/main/java/com/stockforge/inventory/adapter/in/DependencyFailures.java).

---

## 8. Performance Profiling, Virtual Threads & Benchmarks

### Q8.1: Where did you use Java 21 Virtual Threads, and where did you explicitly keep Platform Threads?
* **Interviewer's Focus:** Pragmatic understanding of Project Loom (JEP 444). Virtual threads are not a magic bullet for every service.
* **Concise Answer:** We enabled virtual threads in the **API Gateway** (`GATEWAY_VIRTUAL_THREADS=true`), which is I/O-bound and waits on Redis and downstream gRPC calls. We explicitly kept **Platform Threads** in the **Inventory Service**, because inventory throughput is fundamentally bounded by its 10-connection PostgreSQL database pool.
* **The Technical Truth (Benchmark Evidence):**
  * *In the API Gateway ([docs/benchmarks/platform-vs-virtual-threads.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/platform-vs-virtual-threads.md)):*
    * At 500 req/s: Virtual threads dropped P99 latency from **383–955 ms** (platform threads) down to **119–217 ms**.
    * A request parked waiting on a remote gRPC response consumes only a few hundred bytes of heap on a virtual thread, compared to 1 MB of pre-allocated OS stack space on a platform thread.
  * *In the Inventory Service:*
    * Giving 1,000 virtual threads to Inventory would cause 990 threads to immediately block waiting for a HikariCP database connection. Virtual threads cannot speed up physical database lock serialization.
* **Trap / Follow-up Defense:**
  * *Interviewer:* "What happens when a virtual-thread service is overloaded?"
  * *Defense:* "Platform threads queue excess requests silently in Tomcat's accept queue, masking overload. Virtual threads allow all requests in simultaneously, which would overwhelm downstreams. Therefore, with virtual threads, **explicit concurrency limits (Bulkheads)** are mandatory. In StockForge, the gateway bulkheads (200 concurrent calls) shed excess load immediately with `503 Service Unavailable` and `Retry-After` headers."

---

### Q8.2: Tell me about your Java Flight Recorder (JFR) profiling discovery under container limits.
* **Interviewer's Focus:** Real-world performance profiling and JVM container ergonomics.
* **Concise Answer:** During our initial load test, the gateway exhibited terrible latency (5–7 second P99) and 503 errors at just 200 req/s, despite downstream inventory responding in 15 ms. Profiling with JFR revealed that a Docker 1-CPU container limit was silently forcing the JVM into **Serial GC** with a **single virtual thread carrier thread**, while Linux CFS quota throttled CPU in 243 out of 307 periods!
* **The Technical Truth (Incident Analysis):**
  * In `docs/benchmarks/README.md` and `POSTMORTEM-001/resume`:
    1. The JVM saw `Runtime.getRuntime().availableProcessors() == 1`.
    2. HotSpot defaults to Serial Garbage Collector when CPU count is 1.
    3. The ForkJoinPool carrier thread pool for Virtual Threads had size 1. A single long-running computation or JIT compilation starved all other virtual threads.
    4. JIT compilation was running concurrently with load generation on that single core.
  * *Fix:* Sized containers to 2 CPUs, allocated `-XX:+UseG1GC`, and introduced a 30-second automated warm-up phase in k6 before measuring steady-state performance. P99 immediately dropped from **5–7 seconds to 230 ms**.

---

### Q8.3: How did you avoid misleading benchmark results (warm-up, coordinated omission)?
* **Interviewer's Focus:** Scientific rigor in load testing and benchmarking.
* **Concise Answer:** We used **k6 with arrival-rate executors** to eliminate Coordinated Omission, ran automated 30-second JIT warm-ups before collecting metrics, repeated runs 3 times across fresh instances, and executed an invariant verification script (`check-invariants.sql`) after every run.
* **The Technical Truth (Codebase Evidence):**
  * In `load-tests/reservation-contention.js`:
    * Configured with `executor: 'constant-arrival-rate'` so requests dispatch regardless of whether previous requests have completed, avoiding artificial latency smoothing.
    * Warm-up phase ensures C2 compiler JIT compilation completes before metric capture begins.
  * *Code Reference:* [docs/benchmarks/README.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/benchmarks/README.md).

---

## 9. Design Quality, Testing Strategy & Clean Architecture

### Q9.1: How do you test business rules without starting Spring Boot or a database?
* **Interviewer's Focus:** Testability, test execution speed, and separation of concerns.
* **Concise Answer:** Because our domain layer adheres to Hexagonal Architecture, domain entities and application use cases depend only on pure Java interfaces. We test business rules using **in-memory fakes** (`InventoryFixture.java`), running hundreds of unit tests in **less than 100 milliseconds** with zero Spring context overhead.
* **The Technical Truth (Codebase Evidence):**
  * In `ReserveStockUseCaseTest.java`:
    ```java
    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(); // In-memory fake repositories
        useCase = useCaseWith(fixture.strategy);
        product = fixture.product("SNEAKER-1", 3);
    }
    ```
  * Tests run purely on the JVM heap without Docker, Testcontainers, or Spring Boot test runners, providing instantaneous TDD feedback loops.
  * *Code Reference:* [ReserveStockUseCaseTest.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/inventory-service/src/test/java/com/stockforge/inventory/application/ReserveStockUseCaseTest.java).

---

### Q9.2: Why enforce business invariants in more than one place?
* **Interviewer's Focus:** Software design philosophy and defense-in-depth.
* **Concise Answer:** We enforce invariants across three tiers:
  1. **Domain Layer:** Provides fast, expressive, human-readable error messages for client API responses.
  2. **Database Engine:** Absolute mathematical guarantee (CHECK constraints, foreign keys) guarding against bugs or rogue scripts.
  3. **Reconciliation Jobs & Tests:** Asynchronous audit verifying cross-service eventual consistency.
* **The Technical Truth:**
  * Relying solely on domain code is dangerous because code changes, migrations, or manual SQL fixes can introduce corruption.
  * Relying solely on the database produces ugly SQL exception strings rather than structured RFC 9457 Problem Details.
  * Multi-layer defense gives both great developer ergonomics and unshakeable data integrity.

---

### Q9.3: Which design patterns did you use, and which did you deliberately avoid?
* **Interviewer's Focus:** Architectural decision-making and pattern pragmatism.
* **Concise Answer:**
  * **Patterns Used:**
    * **Ports and Adapters (Hexagonal):** Domain decoupling.
    * **Strategy Pattern:** Swappable reservation algorithms (`StockReservationStrategy`).
    * **Saga Orchestrator:** Centralized order coordination.
    * **Transactional Outbox / Inbox:** Reliable asynchronous messaging.
    * **Value Objects:** Strongly-typed immutable IDs (`ProductId`, `UserId`, `Money`).
  * **Patterns Deliberately Avoided:**
    * **Two-Phase Commit (2PC / XA):** Unacceptable latency and lock-holding across network hops.
    * **Anemic Domain Models:** Entities have private state mutations; no public setters.
    * **Generic ORMs (Hibernate):** Bypassed in favor of explicit Spring Data JDBC SQL control.
    * **Shared Database Integration:** Services never read or write another schema.

---

## 10. Observability, Distributed Tracing & SLO Alerts

### Q10.1: How is distributed tracing propagated across synchronous gRPC and asynchronous Kafka?
* **Interviewer's Focus:** Observability, distributed context propagation, and W3C TraceContext standards.
* **Concise Answer:** We use OpenTelemetry with Micrometer Tracing. Synchronous gRPC injects the `traceparent` header into Protobuf call metadata. For Kafka, `OutboxRelay.java` stores trace headers in the outbox row, extracts them during publish, and injects them into Kafka `ProducerRecord.headers()`, allowing Grafana Tempo to display a single unified trace from HTTP click to notification email.
* **The Technical Truth (Codebase Evidence):**
  * In `OutboxRelay.java`:
    ```java
    Map<String, String> traceHeaders = codec.decodeHeaders(event.headersJson());
    Span span = tracing.startChildOf(traceHeaders, "outbox publish " + event.eventType());
    ```
  * In Kafka consumers, the span context is restored from message headers, linking the consumer execution as a child span of the original user transaction.
  * *Code Reference:* [OutboxRelay.java#L157-L172](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/libs/messaging/src/main/java/com/stockforge/messaging/OutboxRelay.java#L157-L172).

---

### Q10.2: What are your Service Level Objectives (SLOs) and how do multi-window burn-rate alerts work?
* **Interviewer's Focus:** Production SRE standards (Google SRE Workbook methodology).
* **Concise Answer:** We define 4 explicit SLOs: **99.9% Availability**, **P99 < 300 ms Latency**, **Zero Invariant Violations**, and **95% Outbox Freshness (< 1s)**. We alert on **error budget burn rates** using dual time windows (short and long) to avoid alert fatigue from transient spikes while catching sustained outages immediately.
* **The Technical Truth (Codebase Evidence):**
  * *From [docs/observability/slos.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/observability/slos.md):*
    * `ReservationErrorBudgetFastBurn`: $14.4\times$ burn rate across both 5-minute and 1-hour windows (spends 2% of 30-day budget in 1 hour) $\rightarrow$ **PAGES on-call**.
    * `ReservationErrorBudgetSlowBurn`: $6\times$ burn rate across 30-minute and 6-hour windows $\rightarrow$ **Creates Jira ticket**.
    * Correct business outcomes (`409 Insufficient Stock`, `429 Rate Limited`) **do not burn error budget**; only 5xx errors count as failures.
    * Alert rules are unit-tested via `promtool test rules observability/prometheus/alert_rules_test.yml`.
  * *Code Reference:* [docs/observability/slos.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/observability/slos.md).

---

## 11. API Security & OWASP Top 10 Defenses

### Q11.1: How do you defend against Broken Object-Level Authorization (BOLA / OWASP API #1)? Why return 404 instead of 403?
* **Interviewer's Focus:** Modern API security design, defense-in-depth, and resource enumeration prevention.
* **Concise Answer:** The gateway extracts user identity from the cryptographically verified JWT token subject (`sub`). Request bodies never contain a trusted `userId`. The owning service checks object ownership. If Bob tries to access Alice's order, the service returns **404 Not Found**, never 403 Forbidden.
* **The Technical Truth (Codebase Evidence):**
  * Returning `403 Forbidden` confirms to an attacker that the targeted resource ID exists in the system (resource harvesting).
  * Returning `404 Not Found` makes unauthorized IDs completely indistinguishable from non-existent IDs.
  * *Defense in Depth:* The gateway passes `requester_user_id` as a gRPC header. Even if a bug existed in the gateway, `OrderQueries.java` in `order-service` executes:
    ```java
    if (!order.userId().equals(requesterUserId)) {
        return Optional.empty(); // Treated as Not Found!
    }
    ```
  * *Code Reference:* [security.md §API1](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/security.md#L10-L22), [GatewayApiIT.java](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/services/gateway/src/test/java/com/stockforge/gateway/adapter/in/web/GatewayApiIT.java).

---

### Q11.2: How does the system defend against Mass Assignment and Input Tampering (OWASP API #3)?
* **Interviewer's Focus:** Request parsing safety and strict contract enforcement.
* **Concise Answer:** All request DTOs are immutable Java records with explicit fields. Jackson is globally configured with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = true`. Any unexpected field triggers an immediate `400 Bad Request` with an RFC 9457 `malformed-request` problem.
* **The Technical Truth:**
  * Attackers attempting to inject extra JSON parameters (such as `"status": "CONFIRMED"` or `"price": 0.00`) are rejected immediately before reaching use case logic.
  * Outbound responses use strict view records (`Views.*`), preventing internal columns (database sequence IDs, idempotency hashes) from leaking.
  * *Code Reference:* [security.md §API3](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/security.md#L34-L44).

---

## 12. Behavioral Incidents, Real Postmortems & 10× Scaling

### Q12.1: Tell me about a time when your automated tooling or CI gave you false confidence.
* **Interviewer's Focus:** Self-awareness, engineering maturity, and root-cause analysis.
* **Concise Answer:** In POSTMORTEM-001, we discovered that our GitHub Actions CI pipeline had **never actually executed `./mvnw verify`** across weeks of development because the Maven wrapper script (`mvnw`) was committed without the POSIX executable bit (`+x`). CI was failing silently or skipping tests, reporting green checkmarks while broken services passed unnoticed.
* **The Technical Truth (Incident Analysis):**
  * *What Happened:* Git on Windows does not always preserve POSIX permissions (`chmod +x`). The GitHub runner encountered a permission error that was masked by a flawed bash pipeline.
  * *How It Was Found:* During an architectural audit, a clean checkout on Linux failed to boot because a service lacked a required configuration property—yet CI was green.
  * *Remediation:* Fixed file modes in Git (`git update-index --chmod=+x mvnw`), updated the CI workflow with strict error traps (`set -euo pipefail`), and added multi-stage Docker build verifications to CI.
  * *Code Reference:* [POSTMORTEM-001](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/postmortems/POSTMORTEM-001-ci-never-ran-the-build.md).

---

### Q12.2: Tell me about an architectural trade-off you made that you would revisit.
* **Interviewer's Focus:** Technical pragmatism and evolutionary architecture.
* **Concise Answer:** Our decision for the gateway rate limiter to **fail open** during a Redis outage (ADR-010). While it prioritized sales availability during outages, it removed abuse protection. In a future iteration, I would implement a **local in-memory token bucket fallback** inside each gateway instance to cap traffic per node if Redis goes down.
* **The Technical Truth:**
  * Local rate limiting would prevent bot swarms from overwhelming PostgreSQL while still allowing reasonable customer traffic through without a central Redis cluster.
  * *Code Reference:* [ADR-010](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/decisions/ADR-010-rate-limiter-fails-open.md).

---

### Q12.3: What did you intentionally cut from scope, and why?
* **Interviewer's Focus:** Scope discipline and knowing what *not* to over-engineer.
* **Concise Answer:** We intentionally cut a dedicated OAuth2 identity provider, mutual TLS (mTLS) between services, and multi-instance container clustering. Implementing a full Keycloak stack would have distracted from the core engineering challenges: row-level concurrency, distributed saga consistency, and failure modes under load.
* **The Technical Truth:**
  * The gateway validates standard HS256 JWTs (`scripts/dev-token.py`); switching to an external IdP simply means pointing `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` to an issuer without changing application code.
  * *Code Reference:* [docs/security.md](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/security.md), [high_concurrency_inventory_reservation_project_specification.md §43](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/high_concurrency_inventory_reservation_project_specification.md#L1641-L1670).

---

### Q12.4: If traffic surges 10× to 100,000 requests/second on a single hot SKU, what breaks first, and how would you redesign the system?
* **Interviewer's Focus:** Horizontal scalability limits of relational databases and system design trade-offs.
* **Concise Answer:** At 100,000 req/s on a **single product**, the single PostgreSQL row lock becomes a physical bottleneck bounded by SSD commit latency (hundreds to low thousands of transactions per second). To scale 100x:
  1. **Edge Sold-Out Shedding:** Cache a sold-out flag at the edge. The moment 500 units are reserved, 99,500 requests are rejected at the Gateway/CDN in sub-millisecond time.
  2. **Inventory Stock Bucketing (Sub-Rows):** Divide 500 physical units into 50 database rows of 10 units each (`product_id_bucket_1` .. `product_id_bucket_50`). Concurrent reservations hash to random buckets, achieving 50x parallel write throughput on PostgreSQL.
  3. **Asynchronous Queue-Based Allocation:** Convert reservation from synchronous request-reply to asynchronous queuing via Kafka. Push purchase intents into a partitioned Kafka topic and process them using a dedicated single-threaded in-memory sequencer (similar to LMAX Disruptor or Redis Lua), providing guaranteed serialized allocation at 100k+ ops/sec before writing batches to disk.
* **The Technical Truth:**
  * Single-row atomic updates are ideal up to 1,000–2,000 req/s. Beyond that, stock bucketing or serialized in-memory queues are necessary to decouple throughput from disk fsync latencies.
  * *Code Reference:* [docs/career/interview-prep.md#L40-L44](file:///c:/Users/Madhawa/Desktop/Dev/StockForge/docs/career/interview-prep.md#L40-L44).

---

## 13. Key Benchmark Numbers Cheatsheet

| Dimension | Measured Value | Architectural Reason |
| :--- | :--- | :--- |
| **Flash Sale Guarantee** | **0 Oversold** (500/500 units) | PostgreSQL Atomic Conditional `UPDATE ... WHERE available_quantity >= :qty` |
| **Target Load Latency** | **P99: 119–254 ms** | Virtual threads at gateway + minimal lock hold time ($\approx 10\text{ ms}$) |
| **Worker Polling Lag** | **0.38 ms / 0.80 ms** | PostgreSQL Partial Indexes on `WHERE status = 'PENDING'` (vs 328 ms / 55 ms) |
| **Hikari Connection Pool** | **10 connections** | Bounds lock contention; Little's Law capacity $\approx 1,000\text{ req/s}$ |
| **Virtual Threads Impact**| **119 ms vs 383 ms P99** | Eliminates thread stack memory and context switching during gRPC/Redis wait |
| **Optimistic Lock Failure**| **39 units undersold** | Rollback retry loops compound connection starvation on hot rows (191 waiting) |
| **BOLA Defense** | **HTTP 404 Not Found** | Prevents resource enumeration by masking object existence |
| **Outbox Relay Lock** | **`pg_try_advisory_xact_lock`** | Auto-releases on crash; prevents concurrent relay runs per schema |
| **JFR Bottleneck Resolution**| **5–7 s P99 $\rightarrow$ 230 ms** | Sized containers to 2 CPUs, switched from Serial GC to G1GC, added JIT warm-up |
