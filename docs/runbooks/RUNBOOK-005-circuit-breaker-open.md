# RUNBOOK-005: Resilience4j Circuit Breaker Open

## Severity: CRITICAL / HIGH

## 1. Description
The `CircuitBreakerOpen` alert fired because `resilience4j_circuitbreaker_state{state="open"} == 1`. A downstream dependency (`inventoryService`, `orderService`, or `redisCatalogCache`) exceeded its 50% failure rate threshold or slow-call threshold (>2s).

## 2. Immediate Impact
- If `inventoryService` or `orderService` in `api-gateway` opened: incoming HTTP requests to those routes are rejected fast with HTTP 503 `Service Unavailable` and `Retry-After: 5` header.
- If `redisCatalogCache` in `inventory-service` opened: reads bypass Redis and query PostgreSQL directly (graceful cache degradation).

## 3. Diagnosis Steps
1. **Identify the Breaker Name & Service:**
   Query Prometheus:
   ```promql
   resilience4j_circuitbreaker_state{state="open"}
   ```
2. **Inspect Downstream Service Health:**
   - If `inventoryService` circuit opened: check `http://localhost:8081/actuator/health` and inventory logs.
   - If `orderService` circuit opened: check `http://localhost:8082/actuator/health` and order logs.
   - If `redisCatalogCache` opened: check Redis connectivity via `redis-cli ping` and check for slow commands via `redis-cli slowlog get 10`.
3. **Inspect Failure Rate & Slow Call Rate:**
   Query failure rate metrics:
   ```promql
   resilience4j_circuitbreaker_failure_rate{name="<BREAKER_NAME>"}
   resilience4j_circuitbreaker_slow_call_rate{name="<BREAKER_NAME>"}
   ```

## 4. Remediation
1. **Downstream Service Crash:** Restart the failed microservice or container.
2. **Redis Outage:** The system degrades safely to database reads. Restart Redis container when feasible. Once Redis is back up, the circuit breaker enters `HALF_OPEN` after 10s (`wait-duration-in-open-state`), tests 5 probe calls, and automatically closes.
3. **Manual Transition (If needed):**
   Actuator endpoint (if exposed) or service rolling restart will reset the circuit breaker state to `CLOSED`.
