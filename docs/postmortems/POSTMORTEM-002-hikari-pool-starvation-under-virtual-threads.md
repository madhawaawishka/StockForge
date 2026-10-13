# POSTMORTEM-002: HikariCP Connection Pool Starvation Under Virtual Threads

**Date:** 2026-10-10  
**Authors:** Infrastructure & SRE Team  
**Status:** Resolved  
**Severity:** P1 (System Outage / Cascading 504 Gateway Timeouts)  

---

## 1. Executive Summary

Following the activation of Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`) on the API Gateway and Inventory Service, an unannounced traffic spike of 8,500 req/s was generated during load testing. While the JVM memory remained low, HikariCP connection pools in both the Inventory and Order services were completely starved within 4 seconds. 100% of incoming reservation requests backed up and failed with `SQLTransientConnectionException: Connection is not available, request timed out after 3000ms`, precipitating a total gateway outage.

---

## 2. Impact
- **Availability:** API Gateway error rate rose to 98.4% for 6 minutes.
- **Service Degradation:** All database-backed endpoints timed out.
- **MTTD / MTTR:** MTTD: 45 seconds (alert `HikariPoolExhausted`); MTTR: 6 minutes (traffic load shed).

---

## 3. Timeline (UTC)

- **10:15:00** - Load test script initiated with ramp-up to 8,500 concurrent virtual users.
- **10:15:02** - Virtual threads easily absorb 8,500 simultaneous HTTP requests at Tomcat layer.
- **10:15:03** - All 8,500 virtual threads concurrently invoke `InventoryService.reserveStock()`, which attempts to acquire JDBC connections from `inventory-db` pool (`maximum-pool-size = 10`).
- **10:15:04** - 10 connections are active. The remaining 8,490 virtual threads queue in memory waiting for free connections.
- **10:15:07** - 3,000ms `connection-timeout` expires for queued threads. Waves of `SQLTransientConnectionException` begin throwing.
- **10:15:08** - API Gateway gRPC deadlines (2s) trigger `DEADLINE_EXCEEDED` and return HTTP 504.
- **10:15:45** - Prometheus alert `HikariPoolExhausted` fires.
- **10:21:00** - Traffic load halted; connection pools recover to healthy state.

---

## 4. Root Cause Analysis (5 Whys)

1. **Why did requests fail with connection timeouts?**  
   8,490 threads were waiting for 10 database connections for longer than the 3,000ms timeout.
2. **Why were so many requests admitted concurrently?**  
   Virtual threads remove the legacy thread-pool concurrency ceiling (Tomcat default 200 threads). The JVM allowed thousands of requests to enter simultaneously.
3. **Why did the database connections stay busy?**  
   Under 8,500 concurrent callers, row lock contention on hot inventory rows caused individual query latencies to increase from 4ms to 45ms.
4. **Why was there no backpressure at the application gateway?**  
   The API Gateway lacked concurrency bulkheads or rate limiters configured to match downstream database throughput capacity.
5. **Why was pool size not sized or throttled?**  
   The team assumed virtual threads would automatically scale all operations without recognizing that database connection pools act as physical hard caps.

---

## 5. Corrective & Preventative Actions

| Action Item | Type | Owner | Status |
| :--- | :--- | :--- | :--- |
| Configure Resilience4j Semaphore Bulkheads at API Gateway: limit max concurrent in-flight calls to 100 per downstream service | Preventative | Gateway Team | Done |
| Set `Retry-After: 5` and HTTP 503 response when bulkhead is full rather than letting requests enter connection wait queues | Mitigative | Gateway Team | Done |
| Enforce PostgreSQL server-side timeouts: `statement_timeout = 5000`, `lock_timeout = 2000`, `idle_in_transaction_session_timeout = 10000` | Preventative | DB Admin | Done |
| Add Prometheus alert `HikariPoolExhausted` (`hikaricp_connections_pending > 0` for 30s) and corresponding runbook | Detective | Observability | Done |
