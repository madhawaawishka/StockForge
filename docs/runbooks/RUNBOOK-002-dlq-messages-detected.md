# RUNBOOK-002: Dead Letter Queue (DLT) Messages Detected

## Severity: WARNING / HIGH

## 1. Description
The `DeadLetterQueueNotEmpty` alert fired because messages were routed into a Dead Letter Topic (e.g., `payment-events-dlt` or `order-events-dlt`). This happens when an event failed deserialization, schema validation, or exceeded the maximum retry attempts (3 retries with jitter).

## 2. Immediate Impact
Specific asynchronous sagas (such as order confirmation, stock release, or customer notification) have halted for the affected order.

## 3. Immediate Mitigation Steps
1. **Identify the Target Dead Letter Topic:**
   Check Kafka topic consumer lag:
   ```bash
   kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group order-service-payment-group
   ```
2. **Inspect the Dead-Lettered Message:**
   Fetch and view the payload and exception headers from the DLT:
   ```bash
   kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment-events-dlt --from-beginning --max-messages 5 --property print.headers=true
   ```
   Note the exception class and stack trace recorded in the Kafka record headers (`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`).

3. **Check Service Logs for Trace Correlation:**
   Use the `traceId` extracted from the message headers in Loki/Grafana:
   ```text
   {application="order-service"} |= "<TRACE_ID>"
   ```

## 4. Remediation & Replay
Once the root cause is resolved (e.g., downstream schema bug fixed, payment gateway recovered):
1. Use the administrative DLT replay endpoint:
   ```bash
   curl -X POST http://localhost:8082/api/v1/admin/dlt/replay \
     -H "Content-Type: application/json" \
     -d '{
       "targetTopic": "payment-events",
       "key": "<ORDER_ID>",
       "payload": "<MESSAGE_PAYLOAD>"
     }'
   ```
2. Verify the consumer processes the replayed message successfully and inbox deduplication does not drop legitimate retries.
