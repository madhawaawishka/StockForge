# RUNBOOK-002: Messages dead-lettered

**Alert:** `MessagesDeadLettered` — `increase(stockforge_kafka_dead_lettered_total[5m]) > 0` · **Severity:** warning

## What it means
A consumer failed a message 4 times (exponential backoff, ≈ 8 s in total) or could not decode it at all, and moved it
to `<topic>.DLT` on the same partition (ADR-014). The partition is no longer blocked, but the step that message
carried — confirming a reservation, charging a payment, notifying a customer — has not happened.

| Topic | Consumer group | Stalled step if dead-lettered |
| :--- | :--- | :--- |
| `inventory-commands` | `inventory-service` | confirm / release a reservation |
| `payment-commands` | `payment-service` | charge / refund |
| `payment-events` | `order-service-payment-events` | saga reacts to a payment outcome |
| `inventory-events` | `order-service-inventory-events` | saga reacts to confirmation / rejection |
| `order-events` | `notification-worker` | customer notification |

## Confirm
```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 \
  --topic payment-events.DLT --from-beginning --max-messages 5 --timeout-ms 5000 \
  --property print.headers=true --property print.key=true
```
The headers name the failure: `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`,
`kafka_dlt-original-offset`, and `traceparent` for the original trace. Kafka UI
(`docker compose --profile tools up -d kafka-ui`, http://localhost:8090) shows the same.

Search the consumer's logs for the event id (`stockforge-event-id` header) or the trace id.

## Mitigate
- **Transient cause** (database was down, lock timeouts): fix the cause, then replay:
  ```bash
  curl -X POST -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/v1/admin/dead-letters/payment-events/replay?maxMessages=100"
  ```
  Replays are safe: consumers deduplicate by event id (ADR-008), so a message that partly succeeded is not applied twice.
- **Poison message** (`PoisonMessageException`: undecodable or unknown type): replaying will dead-letter it again. It
  usually means a producer and consumer disagree on a contract (ADR-012) — fix and deploy the consumer first.

## Follow-up
Every dead-lettered message is a missing test: reproduce it in the consumer's IT before closing the incident.
