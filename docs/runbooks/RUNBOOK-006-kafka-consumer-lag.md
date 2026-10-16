# RUNBOOK-006: Kafka consumer lag

**Alert:** `KafkaConsumerLagHigh` — a consumer is > 1000 records behind on a partition for 5 min · **Severity:** warning

## What it means
Messages are produced faster than a consumer processes them. Checkout steps and notifications are delayed; nothing is
lost. Under a flash sale a short lag spike is expected — the alert needs 5 minutes.

## Confirm
```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 \
  --describe --group order-service-payment-events
```
Groups: `inventory-service`, `order-service-payment-events`, `order-service-inventory-events`, `payment-service`,
`notification-worker`. Check whether lag sits on one partition (one slow or poisoned aggregate, retrying — see
RUNBOOK-002) or on all of them (consumer too slow or down).

```promql
max by (application) (kafka_consumer_fetch_manager_records_lag_max)
sum by (application) (rate(stockforge_kafka_dead_lettered_total[5m]))
```

## Mitigate
- Consumer down or crash-looping: RUNBOOK-009.
- Consumer slow because its database is slow: RUNBOOK-004.
- Sustained higher load: add instances, up to the topic's partition count (6). Ordering per aggregate is preserved
  because the partition key is the aggregate id.

## Follow-up
If the lag delayed payments long enough for reservations to expire, expect refunds (RUNBOOK-008).
