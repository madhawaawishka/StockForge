# Interview preparation (W6-05)

Short, defensible answers to the questions in spec §41, each tied to something in this repository you can show.
Practise saying them out loud; open the linked file while you do.

## The 60-second story
"StockForge is a flash-sale backend: thousands of people try to buy a few hundred units at once. The hard parts are
not CRUD — they are contention on one database row, consistency across services without distributed transactions, and
behaving well when Redis, Kafka or the database fail. I built five Spring Boot services, a Kafka saga with an outbox,
and then measured: exactly 500 of 500 units sold to 5,000 concurrent buyers, P99 around 200 ms, and fault-injection
tests for every dependency. The most interesting part was what went wrong — a CI pipeline that had never run, a
profile that showed my first benchmark measured JIT warm-up, and a chaos test that caught a wrong error code."

## Concurrency
**What happens when 2,000 requests target one inventory row?** They serialize on the row lock. With the atomic update
each holder keeps the lock for one statement plus commit; PostgreSQL re-evaluates `available >= qty` after the lock is
released, so late arrivals see the new value and fail the predicate instead of overselling. Measured: 500/500 sold,
4,500 clean 409s ([benchmark](../benchmarks/concurrency-strategies.md)).

**Why does a single conditional `UPDATE` prevent overselling under READ COMMITTED?** An `UPDATE` that finds a row
locked by another transaction waits, then re-reads the *latest committed* version and re-checks its `WHERE` clause
(EvalPlanQual). Check and decrement are one atomic step; there is no read-then-write window.

**What race did the naive version have?** Read stock, check in Java, write `available = <computed value>`. Two readers
see 1, both write 0, both succeed — a lost update. `StockReservationConcurrencyIT.naiveReadThenWriteOversells` shows it
on every run, and that the counters stay internally consistent, so even the CHECK constraint cannot notice.

**Pessimistic vs optimistic?** Pessimistic holds the lock across round trips and application code; optimistic retries
on conflict — and on a hot row almost everything conflicts. In the benchmark both starved the 10-connection pool (92
and 191 waiting); optimistic also undersold. Optimistic is for rarely contended rows; a flash sale is the opposite.

**How do you prove stock never goes negative?** Three layers: the domain refuses it, PostgreSQL CHECK constraints
(`available >= 0`, `available + reserved + sold = total`) reject it even if code is wrong, and tests plus a reconciliation
job (alerting) check it continuously. `scripts/check-invariants.sql` runs after every load test.

**Transaction fails halfway?** The idempotency record, purchase-limit counter, stock change, reservation and outbox
event are one transaction — all or nothing. A retry with the same `Idempotency-Key` either replays the stored result
or runs again cleanly.

**Scale one hot SKU to 100k req/s?** One row cannot do it: commit latency bounds it (hundreds to low thousands per
second). Options: reject early once sold out (cached flag at the gateway), split the stock into N sub-rows/buckets and
reserve from a random one, or queue purchase intents (Kafka) and allocate asynchronously — trading immediacy for
throughput. I would measure first; the current design handles 500/s on a laptop with 0 errors.

## Database
**Indexes?** From the queries, not the tables: partial indexes for the workers' "small moving subset" queries (pending
reservations, unpublished events) — 0.8 ms instead of 55 ms, 0.4 ms instead of 328 ms — and a composite
`(user_id, created_at DESC, id)` for keyset pagination. No index on the hot columns of `inventory`: every index slows
every reservation and blocks HOT updates ([query plans](../benchmarks/query-plans.md)).

**Write skew?** Two concurrent reservations by the same user for the same product each see "1 unit held, limit 2"
and both insert — 3 held. Fixed with a counter row updated with `active + qty <= limit` (row lock serializes them) plus
a CHECK constraint. `PurchaseLimitWriteSkewIT` reproduces and guards it.

**Why not a bigger connection pool?** A connection is a server process; more of them add contention, not throughput.
The benchmark shows the pool starving because each request *held* its connection longer under lock contention — a
bigger pool would have added more waiters on the same row ([pool sizing](../benchmarks/connection-pool-sizing.md)).

**Zero-downtime schema changes?** Expand/contract: add nullable columns or new tables, deploy code that writes both,
backfill, switch reads, then drop the old. Example here: `V3__saga_state_messaging_and_audit.sql` adds `saga_step`,
backfills it from `status`, and only then adds the NOT NULL and CHECK constraints.

## Caching
**Why not Redis as the inventory truth?** Redis is not where transactions, constraints and durability live. Redis
caches catalog reads; every reservation re-checks PostgreSQL. Availability from cache is labelled `approximate`.

**Stale data / stampede?** Invalidation after commit (never before — a rollback would leave a wrong cache), short
TTLs, and single-flight on misses so a cold hot product triggers one database read, not thousands
(`ProductCatalogQueriesCacheTest`).

**Redis down?** Reads go to PostgreSQL behind a circuit breaker; the rate limiter fails open with an alert (ADR-010).
Proven with Toxiproxy (`InventoryChaosIT.redisOutageFallsBackToTheDatabase`).

## Distributed systems
**Kafka publish fails after the DB commit?** It cannot be lost: the event is a row in the same transaction (outbox).
The relay publishes later, marks it published only after the broker's ack, and keeps order per aggregate. With Kafka
paused, reservations kept succeeding and events were delivered after recovery (`InventoryChaosIT`).

**Duplicates?** At-least-once delivery is a given. Consumers insert `(group, event_id)` into an inbox table in the same
transaction as their effect — a duplicate is a no-op. Real bug: an earlier version deduplicated by Kafka key (order id),
so the refund for an order was dropped as a "duplicate" of its payment request (POSTMORTEM-004).

**Exactly-once end to end?** Not as delivery. Exactly-once *effect* = at-least-once + idempotent consumers.

**Poison messages?** Four attempts with backoff, then `<topic>.DLT` with exception headers; undecodable messages skip
the retries. Alert, inspect, fix, replay through an admin endpoint (ADR-014).

**Partition keys and ordering?** Key = aggregate id. Ordering is guaranteed per reservation or order, not globally —
which is all the saga needs, and allows parallelism across aggregates (ADR-008).

**Saga, orchestration or choreography?** Orchestration: the workflow is in one class with explicit state
(`status` + `saga_step`, checked by a constraint), timeouts have an owner, and every step is a local transaction plus
an outbox command (ADR-007).

**Compensation fails?** Compensations are commands through the same outbox — retried, idempotent, dead-lettered if they
keep failing, alerted. The order stays in a visible intermediate state (`REFUND_PENDING`) until they complete.

**Payment succeeds after expiry?** Inventory is the authority and never resurrects an expired reservation; it answers
"confirmation rejected" and the saga refunds (ADR-009).

## Reliability and performance
**Timeouts and circuit breakers?** Without a timeout, a slow dependency holds a thread and a connection forever; with
retries on top, a retry storm. Every remote call has a deadline, the gateway has breakers and bulkheads, and business
answers (sold out) never count as failures.

**What was the bottleneck?** First, my benchmark: a cold JVM in a 1-CPU container — Serial GC, one virtual-thread
carrier, JIT compiling during the test. JFR showed CPU throttling in 243 of 307 periods. After fixing sizing and adding
warm-up: the gateway's CPU, then the hot row. Lesson: profile before optimizing; benchmark only warm systems.

**Did virtual threads help?** Yes at the target load (P99 119–217 ms vs 383–955 ms). Past capacity they change *how*
it degrades: platform threads queue silently, virtual threads hit the bulkhead and shed with 503 — so with virtual
threads, explicit concurrency limits are mandatory ([benchmark](../benchmarks/platform-vs-virtual-threads.md)).

**Avoiding misleading benchmarks?** Open-model arrival rates in k6 (no coordinated omission), warm-up per
configuration, repeated runs with the bad ones reported, the environment stated, and an invariant check after each
run.

## Design and code quality
**Why hexagonal?** The domain (pure Java) and use cases are tested with in-memory fakes in milliseconds; adapters are
swappable (strategies, cache); ArchUnit makes the dependency rules executable, so they cannot erode.

**Invariants in more than one place?** Domain for fast, readable errors; database for the guarantee when code is wrong
or someone runs SQL by hand; tests and reconciliation to know when either failed.

## Behavioral
**A bug you found under load.** The first flash-sale run: 5–7 s P99 and hundreds of 503s at 200 req/s while the
inventory service answered in 10–18 ms. I followed the time: gateway client-side gRPC latency of 1.1 s, CPU at 100 %.
JFR showed Serial GC, one carrier thread and heavy throttling from a 1-CPU limit. Fixed JVM sizing, added a warm-up
phase — same workload, P99 230 ms, zero errors.

**A trade-off you would revisit.** Fail-open rate limiting: it keeps sales running during a Redis outage but removes
abuse protection. I'd add a local fallback limiter per gateway instance if abuse were a real threat.

**What you cut.** Real identity provider, mTLS, multi-instance deployment, a schema registry. Each is listed in the
security doc with the next step; none changes the core design.

**What breaks first at 10×?** The gateway CPU (scale out, stateless), then the single hot row (sub-stock buckets or
early rejection), then Kafka partitions per consumer group (6 today).

## Things to be honest about
- CI never ran the build until the Week 6 audit (`mvnw` committed without the executable bit) — and how that was found
  and fixed (POSTMORTEM-001). Owning this is better than being caught by it.
- Numbers are single-machine. The sold-out P99 objective is not met yet.
