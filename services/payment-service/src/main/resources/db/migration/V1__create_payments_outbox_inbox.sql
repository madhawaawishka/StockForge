-- Payments, Outbox, and Inbox schema for Payment Service (spec §10, §16, §20)

CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    order_id            UUID NOT NULL,
    user_id             UUID NOT NULL,
    amount              NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3) NOT NULL,
    status              VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'REFUNDED')),
    simulation_outcome  VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payments_order_id ON payments (order_id);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         JSONB NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}',
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts        INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_payment_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    consumer_group  VARCHAR(64) NOT NULL,
    event_id        VARCHAR(128) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
