# RUNBOOK-004: HikariCP Connection Pool Starvation

## Severity: CRITICAL

## 1. Description
The `HikariPoolExhausted` alert fired because `hikaricp_connections_pending > 0` for >30s or `hikaricp_connections_active / hikaricp_connections_max >= 0.95`. Application worker threads or virtual threads are queued waiting for database connections.

## 2. Immediate Impact
High latency spikes across the service, HTTP 504 Gateway Timeouts, `SQLTransientConnectionException: Connection is not available, request timed out after 3000ms`.

## 3. Diagnosis Steps
1. **Check Active Queries in PostgreSQL:**
   Identify long-running or blocked queries:
   ```sql
   SELECT pid, now() - query_start AS duration, query, state, wait_event_type, wait_event
   FROM pg_stat_activity
   WHERE state != 'idle' AND backend_type = 'client backend'
   ORDER BY duration DESC;
   ```
2. **Check for Lock Contention & Deadlocks:**
   ```sql
   SELECT blocked_locks.pid     AS blocked_pid,
          blocking_locks.pid    AS blocking_pid,
          blocked_activity.query    AS blocked_statement,
          blocking_activity.query   AS blocking_statement
   FROM  pg_catalog.pg_locks         blocked_locks
   JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
   JOIN pg_catalog.pg_locks         blocking_locks 
        ON blocking_locks.locktype = blocked_locks.locktype
        AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
        AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
        AND blocking_locks.page IS NOT DISTINCT FROM blocked_locks.page
        AND blocking_locks.tuple IS NOT DISTINCT FROM blocked_locks.tuple
        AND blocking_locks.virtualxid IS NOT DISTINCT FROM blocked_locks.virtualxid
        AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
        AND blocking_locks.classid IS NOT DISTINCT FROM blocked_locks.classid
        AND blocking_locks.objid IS NOT DISTINCT FROM blocked_locks.objid
        AND blocking_locks.objsubid IS NOT DISTINCT FROM blocked_locks.objsubid
        AND blocking_locks.pid != blocked_locks.pid
   JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
   WHERE NOT blocked_locks.granted;
   ```
3. **Check Connection Leaks:**
   Verify `idle in transaction` sessions:
   ```sql
   SELECT pid, now() - state_change AS idle_duration, query
   FROM pg_stat_activity
   WHERE state = 'idle in transaction';
   ```

## 4. Remediation
1. **Terminate Blocked / Rogue Backends:**
   ```sql
   SELECT pg_terminate_backend(<ROGUE_PID>);
   ```
2. **Server-Side Timeout Safeguards:**
   Verify PostgreSQL timeout settings:
   - `statement_timeout = 5000` (5s max execution)
   - `lock_timeout = 2000` (2s max wait for locks)
   - `idle_in_transaction_session_timeout = 10000` (10s max idle in txn)
3. **DO NOT blindly increase maximum-pool-size:**
   Adding more connections under lock contention worsens PostgreSQL buffer lock thrashing (Little's Law). Keep pool size sized to `(CPU cores * 2) + effective_spindle_count` (spec §24).
