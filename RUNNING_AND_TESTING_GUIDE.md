# StockForge — Complete Running, Testing & Operational Guide

This document is your hands-on reference manual for starting, executing, testing, inspecting, and monitoring **StockForge**. Keep this file handy whenever you want to test the APIs, run high-concurrency benchmarks, observe distributed sagas, or verify database invariants.

---

## 1. Web Portals & Service Endpoints

When the stack is running, open these URLs in your web browser:

| Portal / Tool | URL | Default Credentials / Purpose |
| :--- | :--- | :--- |
| **Swagger UI (OpenAPI)** | [http://localhost:8088/swagger-ui.html](http://localhost:8088/swagger-ui.html) | Interactive REST API tester & documentation (port 8088 via `.env`) |
| **Grafana** | [http://localhost:3000](http://localhost:3000) | Anonymous Admin. Pre-built *StockForge* dashboards |
| **Prometheus** | [http://localhost:9090](http://localhost:9090) | Alerts & SLO recording rules at `/alerts` |
| **Kafka UI** | [http://localhost:8090](http://localhost:8090) | Browse Kafka topics, event payloads, consumer groups & lag |
| **PostgreSQL** | `localhost:5432` | DB: `stockforge`, User: `stockforge_admin`, Pass: `admin_local_only` |
| **Redis** | `localhost:6379` | Cache & token-bucket rate limiter. No password |

---

## 2. Starting & Stopping the Entire Application

Make sure **Docker Desktop** is running. Open **PowerShell** or **Git Bash** in the repository root directory (`c:\Users\Madhawa\Desktop\Dev\StockForge`):

### Start the full platform (all 5 services + infrastructure + observability + Kafka UI):
```bash
docker compose --profile apps --profile tools up -d --build --wait
```

> **Tip (Port 8080 Conflict):** On Windows, system services (such as HTTP.sys) may occupy port 8080. If so, start with an alternate port:
> ```powershell
> $env:GATEWAY_PUBLIC_PORT=8088; docker compose --profile apps --profile tools up -d --build --wait
> ```
> *(Your API gateway will now be at `http://localhost:8088`)*

### Check container health status:
```bash
docker compose ps
```

### Stop the platform:
```bash
docker compose --profile apps --profile tools down
```
*(To wipe database volumes and start completely clean from scratch, add the `-v` flag: `docker compose --profile apps --profile tools down -v`)*

---

## 3. Generating JWT Authentication Tokens

StockForge secures APIs with JWTs signed with a local HMAC development key. Use [`scripts/dev-token.py`](scripts/dev-token.py) to mint tokens instantly:

### In PowerShell:
```powershell
# Admin token (for creating products, stock adjustments, DLT replay)
$ADMIN 

# Customer/Shopper tokens (Alice and Bob)
$ALICE
$BOB   
```

### In Git Bash:
```bash
ADMIN=$(python scripts/dev-token.py --admin)
ALICE=$(python scripts/dev-token.py)
BOB=$(python scripts/dev-token.py)
```

---

## 4. Hands-On API Activities

### Activity A: Interactive Testing with Swagger UI
1. Open [http://localhost:8088/swagger-ui.html](http://localhost:8088/swagger-ui.html).
2. Click the green **Authorize** button at the top right.
3. Paste the token from `python scripts/dev-token.py --admin` (raw string without `Bearer `).
4. Click **Authorize** → **Close**. You can now execute any endpoint directly from the browser!

---

### Activity B: Happy-Path Flash-Sale Checkout (CLI Walkthrough)

Run the following commands in order (PowerShell examples shown; `curl` commands work identically in Git Bash):

#### 1. Create a Product with 100 Units (as Admin):
```powershell
$product = curl.exe -s -X POST http://localhost:8088/api/v1/admin/products `
  -H "Authorization: Bearer $ADMIN" `
  -H "Content-Type: application/json" `
  -d '{"sku":"SNEAKER-LIMITED-42","name":"Flash Sale Sneaker","price":{"amount":"120.00","currency":"USD"},"initialStock":100}' | ConvertFrom-Json

$PRODUCT_ID = $product.id
Write-Host "Created Product ID: $PRODUCT_ID"
```

#### 2. Check Product Availability (Cached with ETag):
```powershell
curl.exe -i -s http://localhost:8088/api/v1/products/$PRODUCT_ID/availability
```
*(Notice the `ETag` header, HTTP 200, and `approximate: true/false`)*

#### 3. Reserve 1 Unit (as Alice) with an Idempotency Key:
```powershell
$RES_KEY = [guid]::NewGuid().ToString()

$reservation = curl.exe -s -X POST http://localhost:8088/api/v1/reservations `
  -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: $RES_KEY" `
  -H "Content-Type: application/json" `
  -d "{`"productId`":`"$PRODUCT_ID`",`"quantity`":1}" | ConvertFrom-Json

$RESERVATION_ID = $reservation.id
Write-Host "Reservation ID: $RESERVATION_ID (Status: $($reservation.status), Expires: $($reservation.expiresAt))"
```

#### 4. Test Idempotency (Re-send the exact same reservation request):
```powershell
curl.exe -i -s -X POST http://localhost:8088/api/v1/reservations `
  -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: $RES_KEY" `
  -H "Content-Type: application/json" `
  -d "{`"productId`":`"$PRODUCT_ID`",`"quantity`":1}"
```
*(Notice the response header: `Idempotent-Replayed: true` and the identical reservation ID! No second reservation is made.)*

#### 5. Place an Order (Asynchronous Saga Trigger):
```powershell
$ORD_KEY = [guid]::NewGuid().ToString()

curl.exe -i -s -X POST http://localhost:8088/api/v1/orders `
  -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: $ORD_KEY" `
  -H "Content-Type: application/json" `
  -d "{`"reservationId`":`"$RESERVATION_ID`"}"
```
*(Notice HTTP status `202 Accepted` and the `Location: /api/v1/orders/<order_id>` header)*

#### 6. Inspect the Order Status & History:
```powershell
$orders = curl.exe -s http://localhost:8088/api/v1/orders -H "Authorization: Bearer $ALICE" | ConvertFrom-Json
$ORDER_ID = $orders.orders[0].id

curl.exe -s http://localhost:8088/api/v1/orders/$ORDER_ID -H "Authorization: Bearer $ALICE" | ConvertTo-Json -Depth 5
```
*(You will see status `CONFIRMED` and its transition history: `PENDING_PAYMENT` → `CONFIRMED`)*

---

### Activity C: Observe Saga Compensation on Payment Failure

StockForge lets you control the simulated payment outcome using the `X-Payment-Simulation` header:

```powershell
# 1. Alice reserves another unit
$RES_KEY_2 = [guid]::NewGuid().ToString()
$res2 = curl.exe -s -X POST http://localhost:8088/api/v1/reservations `
  -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $RES_KEY_2" `
  -H "Content-Type: application/json" -d "{`"productId`":`"$PRODUCT_ID`",`"quantity`":1}" | ConvertFrom-Json

# 2. Place order with payment failure injected
curl.exe -i -s -X POST http://localhost:8088/api/v1/orders `
  -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: $([guid]::NewGuid().ToString())" `
  -H "X-Payment-Simulation: FAILED" `
  -H "Content-Type: application/json" `
  -d "{`"reservationId`":`"$($res2.id)`"}"

# 3. Check Order status after 1-2 seconds:
$failedOrder = (curl.exe -s http://localhost:8088/api/v1/orders -H "Authorization: Bearer $ALICE" | ConvertFrom-Json).orders[0]
Write-Host "Order Status:" $failedOrder.status
```
**What happens under the hood:**
1. Payment Service emits `PaymentFailed` to Kafka.
2. Order Service saga orchestrator catches it, cancels the order, and emits a `ReleaseReservation` command to Kafka.
3. Inventory Service releases reserved stock back to `available_quantity`.
4. Notification Worker logs the cancellation notification.

---

### Activity D: Test Object-Level Authorization (BOLA / Security)

Verify that User B cannot probe or access User A's data:
```powershell
# Bob tries to inspect Alice's order:
curl.exe -i -s http://localhost:8088/api/v1/orders/$ORDER_ID -H "Authorization: Bearer $BOB"
```
*(Returns **404 Not Found** instead of 403, preventing attackers from confirming whether that Order ID exists.)*

---

### Activity E: Test Token-Bucket Rate Limiting

The Gateway has a Redis token-bucket rate limiter:
```powershell
# Fire rapid reservation requests to trip the rate limiter
1..25 | ForEach-Object {
    curl.exe -s -o /dev/null -w "%{http_code}`n" -X POST http://localhost:8088/api/v1/reservations `
      -H "Authorization: Bearer $BOB" `
      -H "Idempotency-Key: $([guid]::NewGuid().ToString())" `
      -H "Content-Type: application/json" `
      -d "{`"productId`":`"$PRODUCT_ID`",`"quantity`":1}"
}
```
*(You will see `429 Too Many Requests` returned with a `Retry-After` header when the burst limit is reached.)*

---

## 5. How to Inspect Databases, Queues & Caches

### 1. PostgreSQL Database Inspection
Connect using any GUI (DBeaver, pgAdmin, VSCode PostgreSQL) on `localhost:5432` (`stockforge_admin` / `admin_local_only` / db: `stockforge`), or through the Docker terminal:

```bash
docker compose exec -it postgres psql -U stockforge_admin -d stockforge
```

Useful SQL queries to run inside `psql`:
```sql
-- 1. View Inventory buckets (Verify stock conservation: available + reserved + sold = total)
SELECT product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity 
FROM inventory.inventory;

-- 2. View Reservations and their statuses
SELECT id, user_id, product_id, quantity, status, expires_at 
FROM inventory.reservations ORDER BY created_at DESC LIMIT 5;

-- 3. View Orders and Saga states
SELECT id, user_id, status, saga_step, total_amount, currency 
FROM ordering.orders ORDER BY created_at DESC LIMIT 5;

-- 4. View Order Transition History
SELECT order_id, from_status, to_status, reason, occurred_at 
FROM ordering.order_status_history ORDER BY occurred_at DESC LIMIT 10;

-- 5. Inspect Transactional Outbox (Pending vs Published events)
SELECT seq, topic, event_type, status, attempts, created_at, published_at 
FROM inventory.outbox_events ORDER BY seq DESC LIMIT 5;

-- 6. Inspect Idempotent Consumer Inbox
SELECT consumer_group, event_id, processed_at 
FROM ordering.processed_events ORDER BY processed_at DESC LIMIT 5;
```

### 2. Run the Invariant Verification Script
Mathematically prove that no stock is negative, all buckets sum to total, and all service states align:

```powershell
# In PowerShell:
.\scripts\verify-invariants.ps1

# In Git Bash / Linux:
bash scripts/verify-invariants.sh
```

### 3. Kafka UI Inspection
Open [http://localhost:8090](http://localhost:8090):
- **Topics**: View `order-events`, `inventory-events`, `payment-events`, `inventory-commands`, `payment-commands`, and `.DLT` (Dead Letter Topics).
- **Messages**: Inspect event envelopes, JSON payloads, headers, and W3C `traceparent`.
- **Consumers**: Check consumer groups, partition assignments, and consumer lag.

### 4. Redis Cache & Token Buckets
```bash
docker compose exec -it redis redis-cli

# Inside redis-cli:
KEYS stockforge:*
# View cached product:
GET stockforge:product:<product_id>
# View rate limit token bucket:
HGETALL stockforge:rate-limit:reservation:<user_id>
```

---

## 6. How to Run Automated Verification & Test Suites

### 1. Automated System & README Verification (44 Live Checks)
With Docker containers running, run the comprehensive verification suite in Git Bash:
```bash
bash scripts/verify-readme.sh
```
*(Executes 44 end-to-end checks covering checkout walkthrough, all status codes [201, 202, 304, 400, 401, 403, 404, 409, 422, 429], BOLA security, DLT replay, Prometheus alert rules, Kafka UI, and traces across all 5 services!)*

### 2. Maven Reactor Build & Integration Tests (Requires JDK 21 on Host)
```powershell
# In PowerShell:
.\mvnw.cmd verify

# In Git Bash / Linux:
./mvnw verify
```
This executes:
- **Unit & State Machine Tests**: Domain models and use cases without Spring.
- **ArchUnit Tests**: Enforces hexagonal architecture rules in all services.
- **jqwik Property Tests**: 500 random operation sequences asserting stock conservation.
- **Testcontainers Tests**: Integration tests against real PostgreSQL 18, Redis 8, and Kafka 4 containers.
- **Toxiproxy Chaos Tests**: Tests network partitions, Redis cutoffs, Kafka pauses, and graceful shutdown.

---

## 7. How to Run High-Concurrency Load Tests (k6)

StockForge includes ready-to-run k6 scenarios inside [`load-tests/`](load-tests/). You do **not** need k6 installed on Windows—it runs directly inside Docker!

### 1. Smoke Test (Verifies system is operational):
```powershell
docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 `
  -v "${PWD}/load-tests:/scripts" grafana/k6:1.3.0 run /scripts/smoke.js
```

### 2. Contention Test (The Core Flash-Sale Benchmark):
Simulates **5,000 concurrent buyers competing for 500 units at 500 requests/second**:
```powershell
docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 `
  -e STOCK=500 -e BUYERS=5000 -e RATE=500 `
  -v "${PWD}/load-tests:/scripts" grafana/k6:1.3.0 run /scripts/reservation-contention.js
```
*During this test:*
- Keep [http://localhost:3000](http://localhost:3000) open to watch Grafana metrics spike in real time.
- After the run, run `.\scripts\verify-invariants.ps1` to verify **exactly 500 units were sold, 4,500 buyers received clean 409 Conflict, and zero units were oversold**.

### 3. Spike Test (Traffic surge from 50 to 600 req/s in 5 seconds):
```powershell
docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 `
  -v "${PWD}/load-tests:/scripts" grafana/k6:1.3.0 run /scripts/spike.js
```

### 4. Full Checkout Flow Load Test:
```powershell
docker run --rm --network stockforge_default -e BASE_URL=http://api-gateway:8080 `
  -v "${PWD}/load-tests:/scripts" grafana/k6:1.3.0 run /scripts/checkout.js
```

---

## 8. Exploring Observability & Distributed Tracing

### 1. Grafana Dashboards
Go to [http://localhost:3000](http://localhost:3000) → Click **Dashboards** → Open folder **StockForge**:
- **StockForge — System Overview**: Shows RED metrics (Request Rate, Error Rate, P50/P95/P99 Durations), DB connection pool saturation, and JVM memory.
- **StockForge — Business Flow & Invariants**: Shows active reservations, sell-out speed, outbox lag, saga compensation counts, and the invariant violation alarm gauge (which must stay at 0).

### 2. Prometheus Alert Rules
Open [http://localhost:9090/alerts](http://localhost:9090/alerts) to view real-time alert evaluation rules (SLO burn-rates, DLQ count, outbox lag, connection pool starvation).

### 3. End-to-End Distributed Tracing in Tempo
1. Go to [http://localhost:3000/explore](http://localhost:3000/explore) → Select datasource **Tempo**.
2. Search for recent traces or paste a `traceId` from an error response or Kafka header.
3. View the trace waterfall spanning:
   `api-gateway (HTTP)` → `inventory-service (gRPC)` → `PostgreSQL` → `Kafka Outbox` → `order-service` → `payment-service` → `notification-worker`.

