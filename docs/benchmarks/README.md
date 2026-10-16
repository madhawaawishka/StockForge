# Benchmarks

Measured results only. Every number on these pages comes from a run on 2026-10-14 that can be repeated with the
commands shown; earlier versions of these pages contained estimated numbers and were replaced.

| Page | Question |
| :--- | :--- |
| [concurrency-strategies.md](concurrency-strategies.md) | Atomic conditional update vs pessimistic vs optimistic locking on one hot product |
| [platform-vs-virtual-threads.md](platform-vs-virtual-threads.md) | Does the gateway serve a flash sale better on virtual threads? |
| [connection-pool-sizing.md](connection-pool-sizing.md) | Why each service has 10 connections, and what pool starvation looks like |
| [query-plans.md](query-plans.md) | `EXPLAIN ANALYZE` of the hot and periodic queries on 2.3 M rows |

## Environment
| | |
| :--- | :--- |
| Host | AMD Ryzen 7 6800H (8 cores / 16 threads), 15.3 GB RAM, Windows 11, NVMe SSD |
| Docker | Docker Desktop 28.0.1, WSL 2 VM with 16 CPUs and 7.4 GB RAM |
| Stack | `docker compose --profile apps` — every service, PostgreSQL 18.6, Redis 8.8.3, Kafka 4.3.1, grafana/otel-lgtm, all on the one host |
| Services | Java 21.0.8 (Temurin JRE), Spring Boot 4.1.1, G1 GC, container limit 2 CPUs / 768 MB each, 10 DB connections each, tracing sampled at 100 % |
| Load | k6 1.3.0 in a container on the same Docker network, open model (`constant-arrival-rate`) |

Everything — load generator, five JVMs, database, broker and observability — shares one laptop. Absolute numbers are
therefore pessimistic and only comparable with each other; the *relative* results and the failure modes are the point.

## Method
1. Start the stack, then **warm up**: 60 s of successful reservations at 200–250 req/s and 30 s of sold-out traffic at
   300 req/s. Restarting a service (to change a strategy or thread model) restarts its JIT, so every configuration
   gets its own warm-up.
2. Run the measured scenario (`load-tests/reservation-contention.js`): a new product with 500 units, then buyers
   arriving at a fixed rate, each a new user with a new idempotency key, for 10 s.
3. Repeat 2–3 times; report every run, including bad ones.
4. After the runs: `scripts/verify-invariants.sh` — every check passed after every configuration.

```bash
docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 \
  -e STOCK=500 -e BUYERS=5000 -e RATE=500 -v "$PWD/load-tests:/scripts" \
  grafana/k6:1.3.0 run /scripts/reservation-contention.js
```

## Headline results (atomic conditional update, virtual threads, warm)
| Metric | Result | Objective |
| :--- | :--- | :--- |
| Units sold in a 500-unit flash sale, 5,000 buyers at 500 req/s | **exactly 500** in every run of every strategy | never more (NFR-1) |
| Reservation P99 at 500 req/s | **119–254 ms** (4 runs) | < 300 ms (NFR-3) |
| Sold-out answer P50 / P99 at 500 req/s | **4–5 ms / 25–119 ms** | P99 < 50 ms (NFR-5) — met in 1 of 4 runs |
| Unexpected responses at 500 req/s | **0** | 0 |
| Throughput sustained without errors | 500 req/s (one 2-CPU gateway); at 800 req/s the bulkhead sheds ≈ 3 % with 503 | — |
| Expiry worker drain rate | **≈ 700 reservations/s** (25,000-reservation backlog) | keeps up |
| Hot-path SQL (`UPDATE … WHERE available >= qty`) | **0.25 ms** | — |

NFR-5 (sold-out P99 < 50 ms) is **not met** on this machine: the median is 5 ms, but the tail includes requests that
queued behind the hot row's successful reservations in the first second of the sale. Rejecting known-sold-out products
from a cached availability flag at the gateway would remove that queueing; it is listed as future work rather than
claimed.

## Profiling: the first runs measured the wrong thing
The first flash-sale runs looked terrible: P99 of 5–7 s and hundreds of 503s at only 200 req/s, with the inventory
service answering each reservation in 10–18 ms. A Java Flight Recorder profile of the gateway explained it:

- With `cpus: 1.0` the JVM saw **one CPU**, so it silently chose the **Serial GC** (58 stop-the-world collections in
  the run) and the virtual-thread scheduler ran on **one carrier thread**.
- The container was **CPU-throttled in 243 of 307 scheduling periods**, and the C2 JIT compiler was the busiest thread:
  the benchmark started on a cold JVM and measured warm-up, not the system.

Fixes, all in the repository: G1 selected explicitly in the image (`JAVA_TOOL_OPTIONS`), 2 CPUs per JVM in compose,
1 vCPU / 2 GB per task in Terraform, and a warm-up phase in the method above. Same workload afterwards: P99 230 ms,
zero errors. The profile is reproducible with
`-XX:StartFlightRecording=filename=/profiling/gateway.jfr,settings=profile` and `jfr print --events
jdk.ExecutionSample,jdk.ThreadCPULoad,jdk.ContainerCPUThrottling`.

## Checkout, spike and soak (2026-10-16)
Same environment, warm stack, nothing else running (an earlier attempt overlapped with image scans that saturated the
disk and is not reported).

| Scenario | Load | Result |
| :--- | :--- | :--- |
| `checkout.js` | ramp to 20 checkouts/s for 2 min, 10 % failed payments | run 1: 2,462 confirmed, 251 compensated, **0 unfinished**; 140 reservations (5 %) shed with 503 during one stall (below). Run 2: 2,578 confirmed, 2 failures in 2,862 reservations. Checkout to terminal state P95 1.3 s |
| `spike.js` | 50 → 600 req/s in 5 s, 80 % reads | **0 broken requests**, P99 14 ms for successful requests, 0 errors in 29,750 requests |
| `soak.js` | 50 req/s for 2 min, reserve + cancel | **0 errors** in 7,184 requests; product reads P99 4.5 ms; reservation P99 468 ms (objective 300 ms — not met, see below) |

**The stall: commit latency on the hot row.** In checkout run 1, inventory's ten connections were all busy for about a
second at 09:41:36 and 09:42:00 while CPU stayed at 10–20 % and no GC pause exceeded 20 ms. Sampling `pg_stat_activity`
during a repeat run showed why: the transaction holding the product's row lock sat in `COMMIT`, waiting on
`IO: WalSync` / `LWLock: WALWrite` for up to **1.4 s**, and every other reservation of the product queued behind it
(`Lock: tuple`). An occasional slow fsync on Docker Desktop's virtual disk therefore freezes all purchases of one
product, the 1 s connection timeout turns the queue into 503s, and the gateway's `inventory` circuit breaker (5 s open
state) extends a 1.4 s stall into a few seconds of shedding. The same stalls explain the soak's reservation tail.

What this means beyond the laptop: on a hot row, **commit (WAL flush) latency is the floor of every sale**, because
the row lock is held until the commit is durable. Production storage with ~1 ms fsync (RDS gp3/io2) makes this a
non-issue at these rates; the levers if it were not are faster WAL storage, `synchronous_commit = off` for the
reservation transaction (trading the last few hundred ms of acknowledged reservations on a crash for latency — not
done here), or splitting a product's stock into several rows.

## Not measured (yet)
- A 30-minute soak (`DURATION=30m`); the runs above are 1–2 minutes.
- Multiple instances per service, or the database on its own host. Horizontal scaling of the gateway is expected to
  move the bottleneck to the hot row (≈ 500–700 successful reservations/s with a 0.25 ms update, which is
  dominated by commit latency on this disk).
