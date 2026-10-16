# RUNBOOK-003: Outbox events stuck or slow

**Alerts:**
- `OutboxEventsStuck` — oldest unpublished event > 30 s for 1 min · critical
- `OutboxPublishLagSloAtRisk` — > 5 % of events take over 1 s to reach Kafka (NFR-7) · warning

## What it means
Business transactions keep succeeding — the outbox row is written in the same transaction — but their events are not
reaching Kafka. Nothing is lost (ADR-008); every downstream step (payment, confirmation, notification) is delayed.

## Confirm
```promql
max by (application) (stockforge_outbox_oldest_pending_age_seconds)
sum by (application) (rate(stockforge_outbox_publish_failures_total[5m]))
```
```sql
SELECT status, count(*), min(created_at), max(attempts), max(last_error)
FROM ordering.outbox_events GROUP BY status;       -- or inventory. / payment.
```
`last_error` and the service log (`OutboxRelay … failed; it stays pending`) give the cause:

| Symptom | Likely cause |
| :--- | :--- |
| `TimeoutException: Topic … not present in metadata` | broker unreachable or topic missing (auto-create is off) |
| failures = 0 but age grows | relay not running: service down, or stuck holding the advisory lock |
| age high only under load | relay batch too small or broker slow; check publish lag P95 |

## Mitigate
1. Broker down: restore Kafka (`docker compose ps kafka`, `docker compose restart kafka`). The backlog drains by itself
   in `seq` order (`InventoryChaosIT` proves it).
2. Topic missing: restart the owning service; it declares its topics at start-up.
3. Relay not running: check the service's health; only one instance per schema relays at a time (advisory lock), so a
   hung instance blocks others — restart it.
4. Throughput: raise `stockforge.messaging.outbox.batch-size` or lower `poll-interval`.

## Follow-up
If events waited long enough for reservations to expire before payment completed, expect refunds
(RUNBOOK-008, ADR-009).
