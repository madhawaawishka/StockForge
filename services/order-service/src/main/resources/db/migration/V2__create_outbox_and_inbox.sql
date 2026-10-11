-- Outbox table and inbox deduplication for reliable messaging (spec §18, §20).

CREATE TABLE outbox_events (
    id             UUID        PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        TEXT        NOT NULL,
    headers        TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_valid CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index for polling unpublished events (spec §13, §20)
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

CREATE TABLE processed_events (
    consumer_group TEXT        NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
