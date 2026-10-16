-- One notification per triggering event (spec §18 inbox, FR-9) and the shared inbox schema.
--
-- The unique event_id is what stops a redelivered event from notifying a customer twice; a notification stays PENDING
-- until delivery succeeded, so a failed delivery is retried instead of being lost.

DROP TABLE processed_events;

CREATE TABLE processed_events (
    consumer_group TEXT        NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pk PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

ALTER TABLE notification_records
    ADD COLUMN event_id UUID,
    ADD COLUMN sent_at  TIMESTAMPTZ;

-- Rows written by the previous version: give them an id of their own; FAILED ones were never delivered.
UPDATE notification_records SET event_id = gen_random_uuid() WHERE event_id IS NULL;
UPDATE notification_records SET sent_at = created_at WHERE status = 'SENT';
UPDATE notification_records SET status = 'PENDING' WHERE status <> 'SENT';

ALTER TABLE notification_records
    ALTER COLUMN event_id SET NOT NULL,
    ADD CONSTRAINT notification_records_event_unique UNIQUE (event_id),
    ADD CONSTRAINT notification_records_status_valid CHECK (status IN ('PENDING', 'SENT')),
    ADD CONSTRAINT notification_records_channel_valid CHECK (channel IN ('EMAIL', 'SMS')),
    ADD CONSTRAINT notification_records_sent_state CHECK ((status = 'SENT') = (sent_at IS NOT NULL));
