-- One payment per order and the shared messaging schema (spec §13, §16, §18, §20).
--
-- V1 created outbox/inbox tables in a different shape from the shared messaging library; no code ever published from
-- them successfully, so they are replaced. The simulated outcome now travels in the command, not on the row.

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

-- A redelivered RequestPayment must find the existing payment, never create a second charge.
DROP INDEX idx_payments_order_id;
ALTER TABLE payments
    ADD CONSTRAINT payments_order_unique      UNIQUE (order_id),
    ADD CONSTRAINT payments_currency_format   CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT payments_timestamps_ordered CHECK (updated_at >= created_at),
    DROP COLUMN simulation_outcome;

COMMENT ON TABLE payments IS 'Simulated payments, one per order. id is an application-generated UUIDv7 (ADR-005).';
