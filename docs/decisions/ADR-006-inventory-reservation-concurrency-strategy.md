# ADR-006: Inventory reservation concurrency control strategy
- Status: Accepted
- Date: 2026-10-09

## Context
Under flash-sale traffic, hundreds or thousands of concurrent purchase requests contend for scarce inventory of a single product (spec §14). A naive application that reads stock in memory, checks availability, and writes it back suffers from lost updates and oversells. We need an explicit concurrency strategy that guarantees zero overselling (NFR-1) while maximizing throughput and minimizing latency.

## Options considered
1. **Naive read-then-write:** Application reads available stock, checks if `available >= requested`, and updates stock without locking or conditional guards. High risk of lost updates and overselling under concurrent load.
2. **Pessimistic row locking (`SELECT ... FOR UPDATE`):** The inventory row is locked exclusively for the duration of the reservation transaction. Concurrent requests wait on the row lock. Correctness is absolute, but lock hold time caps throughput.
3. **Optimistic locking with version column (`WHERE version = :version`):** The application reads the row version, attempts a conditional update, and retries with backoff on conflict. Under high contention on a single hot SKU, version conflicts spike and retries compound database connection utilization.
4. **Atomic conditional update (`UPDATE ... WHERE product_id = :id AND available_quantity >= :qty`):** A single parameterized SQL update handles both stock check and mutation in one round trip. Under PostgreSQL `READ COMMITTED`, concurrent transactions serialize on the row lock and re-evaluate the `WHERE` predicate against the latest committed row.

## Decision
Adopt **Atomic Conditional Update** as the default production strategy (`@Primary` `StockReservationStrategy`). Implement all four approaches behind the common `StockReservationStrategy` port interface in `adapter.out.persistence`, and validate them using a shared concurrency test suite (`StockReservationConcurrencyIT`).

## Consequences
- **Zero application retries:** Atomic conditional update eliminates application retry loops on hot rows; if stock is exhausted, the update returns `0 rows updated` immediately in a single database round trip.
- **Portability and benchmarkability:** Keeping naive, pessimistic, optimistic, and atomic implementations behind `StockReservationStrategy` allows direct comparative benchmarks in k6 (Week 5).
- **Test-proven race condition:** The naive strategy is retained to reproducibly demonstrate overselling in automated tests, serving as evidence for engineering trade-offs.

## Evidence
- Automated integration test `StockReservationConcurrencyIT` runs 50 concurrent threads releasing simultaneously against 10 physical units:
  - `AtomicConditionalUpdateReservationStrategy`: exactly 10 units reserved, 0 oversold, stock conservation holds.
  - `PessimisticLockingReservationStrategy`: exactly 10 units reserved, 0 oversold, stock conservation holds.
  - `OptimisticLockingReservationStrategy`: ≤ 10 units reserved, 0 oversold, stock conservation holds.
  - `NaiveReservationStrategy`: > 5 units reserved for 5 physical units, explicitly proving overselling.

## Addendum — 2026-10-13 (audit before Week 6)
The decision stands; two details above no longer describe the code and are corrected here rather than rewritten:
- Strategies are no longer Spring components with `@Primary`. `ReservationStrategyConfiguration` creates exactly one,
  chosen by `inventory.reservation.strategy` (default `ATOMIC_CONDITIONAL_UPDATE`). The naive strategy can only be
  selected together with `inventory.reservation.allow-unsafe-strategy=true`, so it cannot reach production by accident.
- `StockReservationConcurrencyIT` now runs 400 attempts from 32 concurrent clients against 100 units, each attempt in
  its own transaction released by a latch, repeated 3 times per strategy. Atomic and pessimistic sell exactly 100 and
  reject exactly 300; optimistic never oversells but may give up after 5 retries; naive (50 attempts on 5 units)
  accepts more than 5. End-to-end numbers through the gateway are in
  [docs/benchmarks/concurrency-strategies.md](../benchmarks/concurrency-strategies.md).
