-- Saga state on the order row, reliable messaging tables and the admin audit log (spec §13, §16, §18, §20, FR-10).
--
-- V2 created outbox/inbox tables that no code ever published from successfully (the relay referenced a missing
-- column); they are replaced by the schema of the shared messaging library. Any rows in them were never delivered.

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

-- The orchestrator's state lives on the order (spec §16 B). Existing orders were created by code that never ran a
-- saga; unfinished ones are treated as waiting for payment, which the saga timeout then resolves.
ALTER TABLE orders
    ADD COLUMN saga_step  TEXT,
    ADD COLUMN payment_id UUID;

UPDATE orders
SET saga_step = CASE WHEN status IN ('CONFIRMED', 'CANCELLED', 'REFUNDED') THEN 'COMPLETED'
                     WHEN status = 'REFUND_PENDING' THEN 'REFUNDING'
                     ELSE 'PAYMENT_REQUESTED' END;

ALTER TABLE orders
    ALTER COLUMN saga_step SET NOT NULL,
    ADD CONSTRAINT orders_saga_step_valid
        CHECK (saga_step IN ('PAYMENT_REQUESTED', 'CONFIRMING_RESERVATION', 'REFUNDING', 'COMPLETED')),
    -- Status and saga step must agree; a transition that updates one without the other fails loudly.
    ADD CONSTRAINT orders_saga_step_matches_status CHECK (
        (status = 'PENDING_PAYMENT' AND saga_step IN ('PAYMENT_REQUESTED', 'CONFIRMING_RESERVATION'))
        OR (status = 'REFUND_PENDING' AND saga_step = 'REFUNDING')
        OR (status IN ('CONFIRMED', 'CANCELLED', 'REFUNDED') AND saga_step = 'COMPLETED')),
    ADD CONSTRAINT orders_currency_format CHECK (currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN orders.saga_step IS 'Checkout saga step (SagaStep); the saga timeout only cancels PAYMENT_REQUESTED.';

-- Saga timeout scan: orders still waiting for their payment, oldest first. Partial, so it stays tiny.
CREATE INDEX orders_awaiting_payment_idx ON orders (created_at) WHERE saga_step = 'PAYMENT_REQUESTED';

ALTER TABLE order_items
    ADD CONSTRAINT order_items_currency_format CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE order_status_history
    ADD CONSTRAINT order_status_history_status_valid CHECK (
        to_status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'));

-- Outcomes of deterministic failures are stored for replay too (spec §19); FAILED is no longer used.
DELETE FROM idempotency_keys WHERE status = 'FAILED';
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_status_valid;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED'));

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

CREATE FUNCTION audit_logs_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END
$$;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();
