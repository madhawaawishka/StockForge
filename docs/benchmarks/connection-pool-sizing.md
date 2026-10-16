# Connection pool sizing

**Decision.** Every service has a fixed pool of 10 PostgreSQL connections (`*_DB_POOL_SIZE`), a 1 s
`connection-timeout`, and server-side `statement_timeout` (5 s), `lock_timeout` (2 s) and
`idle_in_transaction_session_timeout` (10 s). Requests that cannot get a connection within a second fail with
503 + Retry-After instead of queueing (spec §23 D, §24).

## Why small
A PostgreSQL connection is a server process. Throughput is bounded by the CPU and disk of the database, not by the
number of connections; past a few connections per core, more of them add context switches and lock contention. For a
hot row it is worse: only one transaction can hold the row lock, so extra connections only add waiters.

Little's law gives the capacity of a pool: **throughput = connections / time each request holds one**. The measured
hold time decides whether 10 is enough.

## Measured
From the flash-sale runs in [concurrency-strategies.md](concurrency-strategies.md) (500 req/s on one product):

| Strategy | Server time per reservation (mean) | Capacity of 10 connections (Little's law) | Max requests waiting for a connection |
| :--- | ---: | ---: | ---: |
| Atomic conditional update | ≈ 10 ms | ≈ 1,000 req/s | **0** |
| Pessimistic locking | grows with the queue on the row lock (not isolated) | below 500 req/s in practice | 92 |
| Optimistic locking | grows with conflicts: up to 5 attempts with sleeps, inside the transaction | below 500 req/s in practice | 191 |

With the atomic update the pool never ran dry at 500 req/s. With the other two, the time each request held its
connection grew with contention — the pool did not shrink, the hold time grew — and starvation followed. A bigger pool
would not have helped: the extra connections would also have waited on the same row lock, while the database
handled more sessions.

## What starvation looks like, and the guard rails
- `hikaricp_connections_pending > 0` for a minute → alert `DatabasePoolStarved` (RUNBOOK-004).
- Requests fail after 1 s with `CannotCreateTransactionException`, which the services answer as 503 / `UNAVAILABLE`
  (POSTMORTEM-006), and the gateway's circuit breaker sheds load until the service recovers.
- Statement and lock timeouts bound how long any one transaction can hold a connection; the chaos test
  `databasePartitionFailsFastWith503AndRecovers` shows the pool recovering on its own after a network partition.

## Budget
Five services × 10 connections = 50, well below PostgreSQL's default `max_connections` of 100, leaving room for
migrations, operators and a second instance of each service. Running more instances means dividing, not multiplying:
keep the *total* per database roughly constant (for example 2 instances × 5 connections), or put PgBouncer in
transaction mode in front of the database.
