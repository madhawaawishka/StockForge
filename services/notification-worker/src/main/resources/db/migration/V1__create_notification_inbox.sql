-- Flyway migration V1: notification inbox and delivery audit table (spec §10, §18)

CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_notification_processed_events PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE notification_records (
    id             UUID         PRIMARY KEY,
    order_id       UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    message        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notification_records_order ON notification_records (order_id);
