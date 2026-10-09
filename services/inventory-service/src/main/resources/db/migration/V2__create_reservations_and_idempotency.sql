-- Reservations, partial indexes, and idempotency keys (spec §13, §15, §19).
-- Enforces invariants in the database so concurrent or buggy writes fail loudly.

CREATE TABLE reservations (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL,
    product_id    UUID        NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity      INT         NOT NULL,
    status        TEXT        NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservations_status_valid      CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT reservations_timestamps_ordered CHECK (updated_at >= created_at),
    CONSTRAINT reservations_expiry_ordered     CHECK (expires_at >= created_at)
);

COMMENT ON TABLE reservations IS 'Stock reservations. id is an application-generated UUIDv7 (ADR-005).';
COMMENT ON CONSTRAINT reservations_status_valid ON reservations IS
    'Only valid states per the state machine: PENDING, CONFIRMED, CANCELLED, EXPIRED.';

-- Partial index for background expiry scan: only PENDING rows where expires_at < now() (spec §13, §21).
CREATE INDEX idx_reservations_pending_expires_at
    ON reservations (expires_at)
    WHERE status = 'PENDING';

-- Composite partial index for purchase-limit queries (spec §13, §15).
CREATE INDEX idx_reservations_user_product_active
    ON reservations (user_id, product_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- Idempotency keys scoped by caller (user_id) to prevent duplicate operations and write replay (spec §13, §19).
CREATE TABLE idempotency_keys (
    key              TEXT        NOT NULL,
    user_id          UUID        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    response_status  INT,
    response_payload TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_keys_pk PRIMARY KEY (user_id, key),
    CONSTRAINT idempotency_keys_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE idempotency_keys IS
    'Idempotency records scoped per (user_id, key). Stored in the same transaction as the business change (spec §19).';

CREATE INDEX idx_idempotency_keys_expires_at
    ON idempotency_keys (expires_at);

-- Per-user active reservation quantity counter table to prevent write-skew anomalies under READ COMMITTED (spec §15).
CREATE TABLE user_product_limits (
    user_id          UUID        NOT NULL,
    product_id       UUID        NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    active_quantity  INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_product_limits_pk PRIMARY KEY (user_id, product_id),
    CONSTRAINT user_product_limits_non_negative CHECK (active_quantity >= 0)
);

COMMENT ON TABLE user_product_limits IS
    'Per-user active reserved + confirmed units. Updated with conditional UPDATE to prevent write skew (spec §15).';
