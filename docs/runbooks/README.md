# Runbooks

One runbook per alert in [`observability/prometheus/alert_rules.yml`](../../observability/prometheus/alert_rules.yml)
(spec §33). Each says what the alert means, how bad it is, how to confirm the cause and what to do first.

Commands assume the local Docker Compose stack; on Git Bash prefix `docker compose exec … /opt/...` with
`MSYS_NO_PATHCONV=1`. Admin API calls need an operator token: `TOKEN=$(python scripts/dev-token.py --admin)`.

| Runbook | Alert(s) | Severity |
| :--- | :--- | :--- |
| [RUNBOOK-001](RUNBOOK-001-stock-invariant-violation.md) | `StockInvariantViolation` | critical |
| [RUNBOOK-002](RUNBOOK-002-dlq-messages-detected.md) | `MessagesDeadLettered` | warning |
| [RUNBOOK-003](RUNBOOK-003-outbox-relay-lag.md) | `OutboxEventsStuck`, `OutboxPublishLagSloAtRisk` | critical / warning |
| [RUNBOOK-004](RUNBOOK-004-connection-pool-starvation.md) | `DatabasePoolStarved`, `ReservationLatencySloBurn` | critical |
| [RUNBOOK-005](RUNBOOK-005-circuit-breaker-open.md) | `CircuitBreakerOpen` | critical |
| [RUNBOOK-006](RUNBOOK-006-kafka-consumer-lag.md) | `KafkaConsumerLagHigh` | warning |
| [RUNBOOK-007](RUNBOOK-007-redis-unavailable.md) | `RateLimiterDegraded` | warning |
| [RUNBOOK-008](RUNBOOK-008-saga-compensations.md) | `SagaCompensationRateHigh` | warning |
| [RUNBOOK-009](RUNBOOK-009-service-down.md) | `ServiceDown` | critical |
| [SLOs](../observability/slos.md) | `ReservationErrorBudgetFastBurn`, `ReservationErrorBudgetSlowBurn` | critical / warning |

Useful everywhere:

```bash
docker compose ps                                   # what is running and healthy
docker compose logs --since 10m inventory-service  # ECS JSON logs; every line carries trace.id
./scripts/verify-invariants.sh                      # database-level invariant check (exits non-zero on violation)
```
