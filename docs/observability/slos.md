# Service level objectives

Four objectives derived from the non-functional requirements (spec §6, §25). Each has an SLI computed from metrics the
services export, a recording rule, an alert, and a panel on the Grafana dashboards. Thresholds come from the spec, not
from what the system happens to achieve; measured values are in [docs/benchmarks](../benchmarks/README.md).

| SLO | Objective | Window | SLI (Prometheus) | Alerts |
| :--- | :--- | :--- | :--- | :--- |
| **SLO-1 Reservation availability** | 99.9 % of `POST /api/v1/reservations` do not fail with 5xx | 30 days | `1 - stockforge:reservation_errors:ratio_rate*` | `ReservationErrorBudgetFastBurn`, `ReservationErrorBudgetSlowBurn` |
| **SLO-2 Reservation latency** (NFR-3) | 99 % of reservations complete within 300 ms at the gateway | 30 days | `1 - stockforge:reservation_slow:ratio_rate*` | `ReservationLatencySloBurn` |
| **SLO-3 Stock correctness** (NFR-1) | zero invariant violations — no oversell, ever | always | `stockforge_inventory_invariant_violations` | `StockInvariantViolation` |
| **SLO-4 Event freshness** (NFR-7) | 95 % of events reach Kafka within 1 s of commit | 30 days | `1 - stockforge:outbox_late:ratio_rate5m` | `OutboxPublishLagSloAtRisk`, `OutboxEventsStuck` |

## How each SLI is measured

**Where.** Reservation SLIs are measured at the gateway — the boundary the customer sees — so they include
authentication, rate limiting, the gRPC hop and the database.

**What counts as good.** `409 insufficient-stock`, `422 purchase-limit-exceeded` and `429 rate-limit-exceeded` are
correct answers, not errors: a sold-out product must not burn the error budget. Only 5xx responses are bad for SLO-1.

**Exact thresholds.** The gateway publishes histogram buckets exactly at 50 ms and 300 ms
(`management.metrics.distribution.slo.http.server.requests`), and the outbox publish-lag timer has a bucket at
1 s. The SLIs are therefore ratios of two counters, not interpolated percentiles:

```promql
# SLO-2: share of reservations slower than 300 ms over 5 minutes
1 - sum(rate(http_server_requests_seconds_bucket{application="api-gateway",method="POST",uri="/api/v1/reservations",le="0.3"}[5m]))
  / sum(rate(http_server_requests_seconds_count{application="api-gateway",method="POST",uri="/api/v1/reservations"}[5m]))
```

**SLO-3** is not a ratio: the reconciliation job compares stock counters with reservation rows and purchase-limit
counters every minute; the database CHECK constraints make the most important violations impossible to commit in the
first place. Any non-zero value pages.

## Error budgets and burn-rate alerts

A 99.9 % objective leaves an error budget of 0.1 % of requests per 30 days. Alerts fire on how fast the budget is being
spent, using two windows so that a short spike does not page and a resolved incident stops paging quickly
(Google SRE Workbook, chapter 5):

| Alert | Burn rate | Windows (both must exceed) | Budget spent if it continues | Action |
| :--- | :--- | :--- | :--- | :--- |
| `ReservationErrorBudgetFastBurn` | 14.4 × | 5 min and 1 h | 2 % per hour | page |
| `ReservationErrorBudgetSlowBurn` | 6 × | 30 min and 6 h | 5 % per 6 hours | ticket |
| `ReservationLatencySloBurn` | 14.4 × of the 1 % latency budget | 5 min and 1 h | 2 % per hour | page |

The rules are unit-tested with `promtool test rules observability/prometheus/alert_rules_test.yml`, including a
synthetic 2 % error rate that must page.

## Error budget policy

- **Budget left:** ship normally.
- **Budget below 25 %:** risky changes (schema migrations, strategy or pool changes) need a load test against the
  compose stack first (`load-tests/reservation-contention.js`, `load-tests/checkout.js`).
- **Budget exhausted:** reliability work takes priority over features until the 30-day window recovers; every
  incident that spent more than 10 % of the budget gets a postmortem (`docs/postmortems/`).

## Limits of these SLOs

- This is a single-node local deployment: the SLOs define the measurement and alerting, they are not a claim of
  production availability.
- Latency is measured server-side at the gateway; client-side latency (network, TLS) would be measured by the load
  tests or real-user monitoring.
