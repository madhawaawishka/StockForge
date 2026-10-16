# POSTMORTEM-007: The first dead-lettered message would never have raised an alert

- **Date found:** 2026-10-16
- **Severity:** Medium — a stalled checkout step could go unnoticed; no data at risk
- **Status:** Fixed

## Summary
`MessagesDeadLettered` fires on `increase(stockforge_kafka_dead_lettered_total[5m]) > 0`. The counter behind it was
created by the code path that dead-letters a message — so it did not exist until the first dead letter, and then
appeared with the value 1. Prometheus' `increase()` needs two samples of a series to see a change; a series whose first
sample is already 1 shows no increase. The first dead-lettered message on a topic, the one that matters most, would never
have alerted. The same pattern affected the saga compensation and refund counters (dashboards showed "No data" instead
of 0, and `SagaCompensationRateHigh` could not see the first compensation) and the rate limiter's fail-open counter.

## Detection
Verifying the README's claim that dashboards and alerts are built on real metrics: every panel and alert expression was
checked against the metric names Prometheus actually held on a running stack. `stockforge_saga_compensations_total`
and `stockforge_saga_refunds_total` did not exist — nothing had compensated yet — which led to the general cause.
The `promtool` unit tests had not caught it because they feed synthetic series that start at 0.

## Root cause
Micrometer registers a tagged counter on first use when it is built inline (`Counter.builder(...).tag(...)
.register(registry).increment()` in the failure path). That is convenient and silently wrong for any counter that an
alert or `rate()` reads: missing-until-first-event series are a known Prometheus pitfall.

## Fix
- `DeadLetterErrorHandling` creates the counter for every consumed topic (`stockforge.messaging.topics.dead-letters`)
  when the error handler is built.
- Saga reasons are now closed enums in the `SagaObserver` port (`Compensation`, `Refund`), and
  `MicrometerSagaObserver` creates every counter at zero on start-up. Mapping the reservation status to a fixed set
  also bounds the label's cardinality, which previously came verbatim from an event field.
- The rate limiter creates its decision counters for every configured policy and outcome.

## Prevention
- `DeadLetterErrorHandlingTest` and `MicrometerSagaObserverTest` assert the series exist at zero before the first event.
- Rule of thumb, now in the code comments: any counter read by an alert is registered eagerly; inline registration is
  only for counters nobody alerts on.

## Lessons
An alert rule can be correct, unit-tested and still blind. Test what the alert *sees* — the series as Prometheus stores
them — not only the expression.
