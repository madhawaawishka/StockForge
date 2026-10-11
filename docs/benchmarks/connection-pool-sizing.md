# HikariCP Connection Pool Sizing & Resource Management (W3-05)

## 1. Pool Sizing Rationale (Spec §24)

A common mistake in backend engineering is sizing the database connection pool based on the number of concurrent HTTP threads (e.g. 100 or 500 connections). In PostgreSQL, each client connection is a dedicated backend OS process. Excessive connections cause:
1. Context switching overhead on the database server.
2. Contention on PostgreSQL buffer cache latches and lock tables.
3. Connection starvation and exhaustion of `max_connections`.

### Formula (HikariCP / Little's Law)
$$\text{Pool Size} = (\text{CPU Cores} \times 2) + \text{Effective Spindle Count}$$

For a 4-core database server with SSD storage:
$$\text{Pool Size} = (4 \times 2) + 1 = 9 \text{ to } 10 \text{ connections}$$

### Throughput Verification with Little's Law
If average transaction duration $T = 5\text{ ms}$ ($0.005\text{ s}$), a pool of 10 connections can service:
$$\text{Throughput} = \frac{10}{0.005} = 2,000 \text{ transactions / second}$$

## 2. Configuration Settings

Configured per service in `application.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 10
      connection-timeout: 250        # ms (fail fast before client timeout)
      idle-timeout: 600000           # 10 min
      max-lifetime: 1800000          # 30 min
      pool-name: InventoryHikariPool
```

## 3. Observability & Micrometer Metrics

HikariCP metrics exposed at `/actuator/prometheus`:
- `hikaricp_connections_active` (currently leased to application threads)
- `hikaricp_connections_idle` (waiting in the pool)
- `hikaricp_connections_pending` (threads blocked waiting for a connection)
- `hikaricp_connections_acquire_seconds` (time spent acquiring a connection)

An alert should fire if `hikaricp_connections_pending > 5` for 10 seconds or if `acquire_seconds > 0.05`.
