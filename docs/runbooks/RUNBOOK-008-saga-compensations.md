# RUNBOOK-008: High checkout compensation rate

**Alert:** `SagaCompensationRateHigh` — more than 20 % of checkouts end compensated for 10 min · **Severity:** warning

## What it means
Orders are being cancelled (stock released) instead of confirmed, or payments refunded. The saga is working — this is
the compensation path (ADR-007) — but customers are not getting what they tried to buy.

## Confirm
```promql
sum by (reason) (rate(stockforge_saga_compensations_total[10m]))
sum by (reason) (rate(stockforge_saga_refunds_total[10m]))
```
| Metric · `reason` | Typical cause | Next step |
| :--- | :--- | :--- |
| compensations · `PAYMENT_FAILED` | provider declining (real or simulated outcomes) | payment-service logs |
| compensations · `PAYMENT_TIMEOUT` | no outcome within `ORDER_PAYMENT_TIMEOUT` (2 min) | RUNBOOK-006 (payment consumers lagging), RUNBOOK-003 (outbox stuck) |
| refunds · `LATE_PAYMENT` | a payment succeeded after the order had been cancelled | as for `PAYMENT_TIMEOUT` |
| refunds · `RESERVATION_EXPIRED` | events delayed past the 10 min reservation TTL | RUNBOOK-003 / RUNBOOK-006 (ADR-009) |

```sql
SELECT status, saga_step, count(*) FROM ordering.orders
WHERE updated_at > now() - interval '30 minutes' GROUP BY 1, 2;
SELECT to_status, reason, count(*) FROM ordering.order_status_history
WHERE occurred_at > now() - interval '30 minutes' GROUP BY 1, 2 ORDER BY 3 DESC;
```

## Mitigate
Fix the upstream cause. Orders stuck in `REFUND_PENDING` mean refund commands are not completing — check the
`payment-commands` consumer and its dead-letter topic (RUNBOOK-002).

## Follow-up
Run `./scripts/verify-invariants.sh` once traffic is quiet: orders, payments and reservations must agree.
