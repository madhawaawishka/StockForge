-- Runs once, when the local PostgreSQL data volume is first created (docker-entrypoint-initdb.d).
--
-- One login role and one schema per service, so each service can reach only its own tables (ADR-002).
-- Passwords exist only in this local Docker Compose stack and are not secrets; real environments
-- provision roles and credentials through their own secret management.

-- Query statistics for performance work (spec §13, §26).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Nothing is reachable by default: no implicit access to the database or the public schema.
REVOKE ALL ON DATABASE stockforge FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE ROLE inventory_svc LOGIN PASSWORD 'inventory_local_only';
CREATE SCHEMA inventory AUTHORIZATION inventory_svc;

CREATE ROLE order_svc LOGIN PASSWORD 'order_local_only';
CREATE SCHEMA ordering AUTHORIZATION order_svc;

CREATE ROLE payment_svc LOGIN PASSWORD 'payment_local_only';
CREATE SCHEMA payment AUTHORIZATION payment_svc;

GRANT CONNECT ON DATABASE stockforge TO inventory_svc, order_svc, payment_svc;
