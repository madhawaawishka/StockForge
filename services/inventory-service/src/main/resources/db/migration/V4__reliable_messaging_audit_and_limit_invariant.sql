-- Reliable messaging, admin audit log and a database-enforced purchase limit (spec §13, §15, §18, §20, FR-10).
--
-- V3 created outbox/inbox tables that no code ever published from successfully (the relay referenced a missing
-- column). They are replaced by the schema of the shared messaging library
-- (libs/messaging/src/main/resources/stockforge/messaging/schema-postgresql.sql). Any rows in them were never
-- delivered, so nothing of value is dropped.

DROP TABLE outbox_events;
DROP TABLE processed_events;

CREATE TABLE outbox_events (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    id             UUID        PRIMARY KEY,
    topic          TEXT        NOT NULL,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    headers        JSONB       NOT NULL,
    status         TEXT        NOT NULL,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ,
    CONSTRAINT outbox_events_seq_unique      UNIQUE (seq),
    CONSTRAINT outbox_events_status_valid    CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT outbox_events_published_state CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT outbox_events_attempts_valid  CHECK (attempts >= 0)
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (seq) WHERE status = 'PENDING';
CREATE INDEX outbox_events_published_idx ON outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE TABLE processed_events (
    consumer_group TEXT        NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pk PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

-- Purchase limit enforced in the database as well as in code (spec §9 F, §15). The limit is configuration, so each
-- counter row carries the limit it was last checked against and a CHECK constraint ties the two together: a code path
-- that forgets the conditional UPDATE fails loudly instead of letting a user exceed the limit.
ALTER TABLE user_product_limits
    ADD COLUMN limit_quantity INT NOT NULL DEFAULT 2,
    ADD CONSTRAINT user_product_limits_limit_positive CHECK (limit_quantity > 0),
    ADD CONSTRAINT user_product_limits_within_limit   CHECK (active_quantity <= limit_quantity);

COMMENT ON COLUMN user_product_limits.limit_quantity IS
    'Per-user limit in force when active_quantity was last increased (inventory.reservation.purchase-limit).';

-- Idempotent replay stores the outcome of deterministic failures too (spec §19), so FAILED is no longer used.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_status_valid;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED'));
UPDATE idempotency_keys SET status = 'COMPLETED' WHERE status = 'FAILED';

-- Append-only record of administrative actions (spec §28, FR-10).
CREATE TABLE audit_logs (
    id             UUID        PRIMARY KEY,
    actor_id       TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    metadata       JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX audit_logs_aggregate_idx ON audit_logs (aggregate_type, aggregate_id, created_at);

COMMENT ON TABLE audit_logs IS 'Append-only audit trail of administrative actions; rows are never updated or deleted.';

-- The service role owns the table, so privileges alone cannot make it append-only; a trigger can.
CREATE FUNCTION audit_logs_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END
$$;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();
