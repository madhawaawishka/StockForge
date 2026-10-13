# RUNBOOK-003: Transactional Outbox Relay Lag

## Severity: CRITICAL / HIGH

## 1. Description
The `OutboxEventStuck` alert fired because `stockforge_outbox_oldest_pending_age_seconds > 60` for more than 1 minute. Outbox events have accumulated in the database and are not being dispatched to Kafka topics.

## 2. Immediate Impact
Asynchronous domain events (`OrderCreated`, `StockReserved`, `PaymentRequested`) are stalled. Downstream consumers will not process sagas, causing timeouts and stuck orders.

## 3. Diagnosis Steps
1. **Check Kafka Broker Availability:**
   Verify if the Kafka broker is responding:
   ```bash
   kafka-broker-api-versions.sh --bootstrap-server localhost:9092
   ```
2. **Inspect the Outbox Table in the Affected Service:**
   Query the pending outbox records to determine failure reason:
   ```sql
   SELECT id, event_type, status, attempts, last_error, created_at, NOW() - created_at AS lag_duration
   FROM ordering.outbox_events
   WHERE status = 'PENDING'
   ORDER BY created_at ASC
   LIMIT 10;
   ```
3. **Inspect Application Relay Logs:**
   Filter logs in Loki or console for `OutboxRelayWorker`:
   ```text
   {application="order-service"} |= "OutboxRelayWorker"
   ```
   Look for `TimeoutException`, `LeaderNotAvailableException`, or serialization errors.

## 4. Remediation
1. **Kafka Broker Down:** Restart or scale Kafka broker instance. Once Kafka resumes, `OutboxRelayWorker` will automatically resume draining the queue on the next scheduled tick (500ms).
2. **Poison Pill Event:** If a specific event has invalid JSON or exceeds max message size:
   Update its status to `FAILED` with an error note to allow subsequent messages to process:
   ```sql
   UPDATE ordering.outbox_events
   SET status = 'FAILED', last_error = 'Quarantined by SRE operator: payload too large'
   WHERE id = '<STUCK_EVENT_ID>';
   ```
3. **Verify Drain:**
   Watch `stockforge_outbox_oldest_pending_age_seconds` gauge decrease to near 0.
