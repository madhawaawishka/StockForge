# Benchmark Notes: Inventory Reservation Concurrency Strategies

Comparison of concurrency control strategies for inventory reservation under high contention (spec §14, §26).

## Environment Specification
- **Hardware:** Intel Core i7 / AMD Ryzen (Reference local dev machine)
- **OS:** Windows 11 / WSL2 Linux
- **JVM:** OpenJDK 23 (target bytecode: Java 21)
- **Database:** PostgreSQL 18.6 (Docker, shared instance, 10 connections pool)
- **Load tool:** k6 / Testcontainers concurrent latch executors

---

## Evaluated Strategies
1. **Atomic Conditional Update (`ATOMIC_CONDITIONAL_UPDATE`):**
   - Single `UPDATE inventory ... WHERE product_id = :id AND available_quantity >= :qty`.
   - Zero application retries, single database round trip.
2. **Pessimistic Locking (`PESSIMISTIC_LOCKING`):**
   - `SELECT ... FOR UPDATE` followed by stock update and reservation insert.
   - Strict serialization on the row lock.
3. **Optimistic Locking (`OPTIMISTIC_LOCKING`):**
   - Read version, update with `WHERE version = :version`, backoff and retry up to 5 attempts.
4. **Naive Read-Then-Write (`NAIVE`):**
   - Read stock, application validation, unconditional update. Test-only benchmark to prove lost update race.

---

## Test Scenarios & Invariant Verification

### Scenario A: Flash Sale Single Hot SKU
- **Initial stock:** 100 physical units.
- **Concurrent requests:** 1,000 clients attempting to reserve 1 unit simultaneously.
- **Expected invariant:**
  - Zero oversold units: `count(reservations in PENDING/CONFIRMED) == 100`.
  - Stock conservation: `available + reserved + sold == total`.
  - Exactly 900 requests receive 409 `insufficient-stock`.

### Comparative Metrics Matrix (Template)

| Strategy | Throughput (req/s) | P50 (ms) | P95 (ms) | P99 (ms) | Conflicts/Retries | Oversold Units | Invariant Violations |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Atomic Conditional Update** | *TBD (Week 5 k6)* | *TBD* | *TBD* | *TBD* | 0 | **0** | **0** |
| **Pessimistic (`FOR UPDATE`)** | *TBD (Week 5 k6)* | *TBD* | *TBD* | *TBD* | 0 | **0** | **0** |
| **Optimistic Locking** | *TBD (Week 5 k6)* | *TBD* | *TBD* | *TBD* | High | **0** | **0** |
| **Naive (Lost Update)** | *N/A* | *N/A* | *N/A* | *N/A* | 0 | **> 0 (Proven)** | **> 0** |

---

## Automated Concurrency Test Evidence
Automated test suite: `StockReservationConcurrencyIT` (runs on real PostgreSQL 18 with Testcontainers):
- `atomicConditionalUpdateNeverOversells`: Verified 0 oversold, invariant preserved.
- `pessimisticLockingNeverOversells`: Verified 0 oversold, invariant preserved.
- `optimisticLockingNeverOversells`: Verified 0 oversold, invariant preserved.
- `naiveStrategyOversellsUnderContention`: Verified overselling occurs (> 5 reservations created for 5 physical units).
