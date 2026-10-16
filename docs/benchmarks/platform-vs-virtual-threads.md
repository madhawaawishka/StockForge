# Platform vs virtual threads in the gateway

**Question.** The gateway spends most of each request waiting: on Redis (rate limit) and on a gRPC call to the
inventory service. Does it serve a flash sale better on virtual threads (`spring.threads.virtual.enabled`) than on
Tomcat's default pool of 200 platform threads (spec §24)?

**Answer.** Yes at the target load — reservation P99 fell from 383–955 ms to 119–217 ms at 500 req/s. Under overload
the two behave differently, and that difference matters more than the speed-up: platform threads queue excess
requests silently, virtual threads let them through to the bulkhead, which sheds them with an explicit 503.

Environment and method: [README.md](README.md). Atomic strategy, gateway limited to 2 CPUs, each configuration warmed
up after its restart, two runs each.

## Results
| Gateway threads | Rate | Run | Sold | Unexpected (503) | Reserved P50 / P95 / P99 | Sold-out P50 / P95 / P99 |
| :--- | ---: | :---: | ---: | ---: | :--- | :--- |
| Platform (200) | 500/s | 1 | 500 | 0 | 242 / 296 / 383 ms | 4 / 17 / 218 ms |
| | | 2 | 500 | 7 | 460 / 922 / 955 ms | 9 / 778 / 946 ms |
| **Virtual** | 500/s | 1 | 500 | **0** | 134 / 178 / 217 ms | 5 / 10 / 119 ms |
| | | 2 | 500 | **0** | 62 / 99 / 119 ms | 4 / 7 / 25 ms |
| Platform (200) | 800/s | 1 | 500 | 0 | 268 / 587 / 690 ms | 18 / 469 / 615 ms |
| | | 2 | 500 | 0 | 272 / 454 / 647 ms | 6 / 244 / 429 ms |
| **Virtual** | 800/s | 1 | 500 | 238 | 344 / 466 / 508 ms | 6 / 56 / 312 ms |
| | | 2 | 500 | 217 | 259 / 447 / 474 ms | 6 / 31 / 327 ms |

## Interpretation
- **At 500 req/s** virtual threads remove the thread pool as a queue: a request waiting on gRPC costs a few hundred
  bytes of heap instead of a 1 MB stack and a pool slot, so the CPU goes to work instead of context switches.
- **At 800 req/s** the gateway (2 CPUs) is past its capacity. With platform threads, at most 200 requests are inside
  the gateway at once; the rest wait in Tomcat's accept queue — latency grows, nothing fails, and nothing tells the
  client to back off. With virtual threads every request is accepted and reaches the `inventory` **bulkhead** (200
  concurrent calls); about 3 % found it full and got `503 service-unavailable` with `Retry-After` immediately, which is
  the load-shedding behaviour spec §23 D asks for. The gateway's logs show the reason for each one:
  `The inventory service is saturated; retry shortly`.
- Virtual threads therefore need an explicit concurrency limit in front of every downstream — the bulkhead is not
  optional with them. The inventory service keeps platform threads (`INVENTORY_VIRTUAL_THREADS=false`): its work is
  bounded by 10 database connections, so more concurrent threads would only queue for them. That choice was not
  measured separately.
- No virtual-thread pinning was recorded (`jdk.VirtualThreadPinned` events: 0 in the JFR profile).

## Side effect worth knowing
At 800 req/s the gateway's Redis calls for rate limiting occasionally exceeded their 100 ms timeout (205 failures in
the window) and the limiter failed open as designed (ADR-010, `stockforge_rate_limit_decisions_total{outcome=
"fail_open"}`). CPU saturation of the caller looks like a slow dependency — another reason to scale the gateway before
it reaches 100 % CPU.

Reproduce:
```bash
GATEWAY_VIRTUAL_THREADS=false docker compose --profile apps up -d --wait api-gateway
# warm up, then: -e STOCK=500 -e BUYERS=5000 -e RATE=500 (and RATE=800, BUYERS=8000)
```
