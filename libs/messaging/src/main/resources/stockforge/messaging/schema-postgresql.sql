-- Reference schema of the messaging tables (spec §18, §20). It is NOT applied automatically: every service copies it
-- into its own Flyway migration, because each service owns its schema and database role (ADR-002). The library's
-- integration test runs against exactly this file, and each service's integration tests run the relay against its
-- own migration, so drift between the two fails a build.

-- Transactional outbox. One row per message, written in the same transaction as the business change.
CREATE TABLE outbox_events (
    -- Insertion order. The relay publishes by seq, which keeps the order of one aggregate's messages because writers
    -- of the same aggregate are serialized by the aggregate's row lock.
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    id             UUID        PRIMARY KEY,           -- the envelope's eventId
    topic          TEXT        NOT NULL,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,              -- the Kafka record key
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,              -- the complete envelope
    headers        JSONB       NOT NULL,              -- W3C trace context captured at write time
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

-- Relay poll and lag metric: pending rows in publish order. Partial, so it stays tiny however large the table grows.
CREATE INDEX outbox_events_pending_idx ON outbox_events (seq) WHERE status = 'PENDING';
-- Retention job: published rows by age.
CREATE INDEX outbox_events_published_idx ON outbox_events (published_at) WHERE status = 'PUBLISHED';

-- Inbox of idempotent consumers: event ids already applied, per consumer group.
CREATE TABLE processed_events (
    consumer_group TEXT        NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pk PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
