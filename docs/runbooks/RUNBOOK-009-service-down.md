# RUNBOOK-009: Service down

**Alert:** `ServiceDown` — Prometheus cannot scrape a service at any of its addresses for 2 min · **Severity:** critical

## What it means
The service is not running, is restarting, or is up but not answering on its management endpoint. Effects by service:

| Service | Effect while down |
| :--- | :--- |
| api-gateway | the public API is unavailable |
| inventory-service | reservations fail (gateway 503); order creation fails; commands queue in Kafka |
| order-service | order creation and order reads fail; saga events queue in Kafka |
| payment-service | orders wait for payment; after 2 min they are cancelled (RUNBOOK-008) |
| notification-worker | notifications are delayed only; they are sent after recovery |

## Confirm
```bash
docker compose ps
docker compose logs --tail 200 order-service
curl -s localhost:8180/actuator/health/readiness     # gateway (management port); others from inside the network:
docker compose exec order-service curl -s localhost:8082/actuator/health/readiness
```
Readiness includes the database, liveness does not: a service whose database is down is *not ready* (removed from
load balancing) but is not restarted.

Common start-up failures: missing required environment variable (no defaults for secrets and database URLs), Flyway
migration failure, the database or Kafka unreachable.

## Mitigate
Fix the cause and restart (`docker compose up -d <service>`). Shutdown and start-up are graceful: in-flight requests
finish (W6-02, `InventoryChaosIT.aStoppingInstanceDrainsInFlightRequestsAndLosesNoEvents`) and unpublished events stay
in the outbox.
