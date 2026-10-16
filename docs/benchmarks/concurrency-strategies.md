# Reservation concurrency strategies under a flash sale

**Question.** Which strategy should protect the hot inventory row: an atomic conditional `UPDATE`, a pessimistic
`SELECT … FOR UPDATE`, or optimistic locking with a version column (spec §14, ADR-006)?

**Answer.** The atomic conditional update. All three sold exactly 500 of 500 units — none oversold — but only the atomic
update did it without errors. The other two held database connections while waiting (for a row lock, or between
retries), exhausted the 10-connection pool, and pushed the gateway's circuit breaker open. Optimistic locking also
**undersold**: it turned buyers away while 35–39 units were still in stock.

Environment and method: [README.md](README.md). Each strategy ran on a warmed-up stack: 500 units, 5,000 buyers at
500 req/s, every buyer a new user.

## Results
| Strategy | Run | Sold | Sold out (409) | Unexpected | Reserved P50 / P95 / P99 | Sold-out P50 / P99 |
| :--- | :---: | ---: | ---: | ---: | :--- | :--- |
| **Atomic conditional update** | 1 | 500 | 3,779 | 722 | 881 / 1,079 / 1,152 ms | 5 / 933 ms |
| | 2 | 500 | 4,501 | **0** | 156 / 219 / 254 ms | 5 / 114 ms |
| | 3 | 500 | 4,500 | **0** | 132 / 190 / 230 ms | 5 / 91 ms |
| **Pessimistic locking** | 1 | 500 | 231 | 4,270 | 600 / 1,061 / 1,207 ms | 951 / 1,105 ms |
| | 2 | 500 | 1,334 | 3,167 | 825 / 1,102 / 1,222 ms | 174 / 947 ms |
| | 3 | 500 | 4,374 | 127 | 259 / 624 / 686 ms | 13 / 647 ms |
| **Optimistic locking** (5 retries) | 1 | **461** | 0 | 4,540 | 947 / 1,113 / 1,182 ms | — |
| | 2 | — | — | — | setup failed: the breaker was still open from run 1 | — |
| | 3 | **465** | 0 | 4,536 | 912 / 1,114 / 1,149 ms | — |

"Unexpected" responses were `503 service-unavailable`. Atomic run 1 still carried JIT warm-up (the inventory service
had just been recreated); runs 2 and 3 are representative. After all runs, `scripts/verify-invariants.sh` found no
violation for any strategy.

## Why — what the metrics showed
| Signal (Prometheus, during the measured runs) | Atomic | Pessimistic | Optimistic |
| :--- | ---: | ---: | ---: |
| Inventory requests waiting for a DB connection (max) | **0** | 92 | 191 |
| Calls rejected by the gateway's open circuit breaker | 0 | 1,966 | 13,542 |
| Transactions that could not get a connection (`CannotCreateTransactionException`) | 0 | — | 684 |

- **Atomic conditional update** — `UPDATE inventory SET available = available - :q … WHERE product_id = :id AND
  available >= :q` checks and decrements in one statement (0.25 ms in [query-plans.md](query-plans.md)). Concurrent
  transactions queue on the row lock inside PostgreSQL for the shortest possible time; once stock reaches zero the
  predicate fails without waiting and the answer is an immediate 409.
- **Pessimistic locking** — `SELECT … FOR UPDATE`, decide in Java, then `UPDATE`. The lock is held for two round trips
  plus application code, and every waiting transaction holds a pool connection while it waits. At 500 req/s the
  pool's ten connections were all parked behind one row lock; new requests waited up to 1 s for a connection, then
  failed with 503.
- **Optimistic locking** — read the version, `UPDATE … WHERE version = :v`, retry with backoff on conflict. On one hot
  row almost every attempt conflicts. The retries (up to 5) happen inside the open transaction and sleep between
  attempts, so each request holds its connection longer the more it conflicts and the pool starves even faster; requests that ran out of retries or connections were rejected even though stock remained — the
  undersell. Optimistic locking suits rows that are rarely contended, which is exactly what a flash sale is not.

## Unit-level evidence
`StockReservationConcurrencyIT` (part of `./mvnw verify`) runs 400 attempts from 32 threads against 100 units, each in
its own transaction released by a latch, three times per strategy: atomic and pessimistic sell exactly 100 and reject
300; optimistic never oversells but may sell fewer; the naive read-modify-write (test-only) oversells on every run.

## Decision
Keep `ATOMIC_CONDITIONAL_UPDATE` as the default (ADR-006). The other strategies stay selectable
(`INVENTORY_RESERVATION_STRATEGY`) for comparison; the naive one requires an explicit unsafe flag.

Reproduce one strategy:
```bash
INVENTORY_RESERVATION_STRATEGY=PESSIMISTIC_LOCKING docker compose --profile apps up -d --wait inventory-service
# warm up, then run load-tests/reservation-contention.js as in README.md
```
